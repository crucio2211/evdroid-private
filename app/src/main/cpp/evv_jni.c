/* The bridge between the ECI engine and Android's synthesis callback.
 *
 * The engine hands samples over on a thread of its own and blocks in the
 * callback until the caller has taken them, so the shape here is a ring buffer
 * with a lock either side of it: the engine's thread fills it and Android's
 * synthesis thread drains it. That blocking is the point rather than a cost.
 * It paces the engine to whatever the audio track is taking, so a long
 * utterance does not have to be held in memory before any of it is heard.
 *
 * No JNI call is ever made from the engine's thread, which is why there is no
 * thread to attach and none to detach.
 */

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>

#include "eci.h"

#define TAG "evvdroid"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* What the engine writes at a time, in samples, and what the ring holds, in
   bytes. The ring has to be bigger than one frame or the callback would wait
   for room that can never arrive. */
#define FRAME_SAMPLES 1024
#define RING_BYTES    (128 * 1024)

/* How many milliseconds the end-of-utterance wait will spend watching for the
   engine to take the work up before it stops waiting for that. */
#define START_SPINS 200

typedef struct {
	ECIHand         eci;
	pthread_mutex_t lock;
	pthread_cond_t  room;
	pthread_cond_t  filled;
	unsigned char*  ring;
	size_t          head;
	size_t          tail;
	size_t          count;
	int             aborted;
	int             started;
	int             done;
	pthread_t       worker;
	pthread_cond_t  work;
	int             running;
	int             pending;
	int             busy;
	int             quitting;
	int             dictionary_leases;
	int             dictionary_replacing;
	unsigned char*  text;
	ECIDictHand     dict;
	short           frame[FRAME_SAMPLES];
} instance;

static instance* unwrap(jlong handle) {
	return (instance*)(intptr_t)handle;
}

static void put(instance* in, const unsigned char* src, size_t n) {
	size_t first = RING_BYTES - in->head;
	if (first > n)
		first = n;
	memcpy(in->ring + in->head, src, first);
	memcpy(in->ring, src + first, n - first);
	in->head = (in->head + n) % RING_BYTES;
	in->count += n;
}

static void take(instance* in, unsigned char* dst, size_t n) {
	size_t first = RING_BYTES - in->tail;
	if (first > n)
		first = n;
	memcpy(dst, in->ring + in->tail, first);
	memcpy(dst + first, in->ring, n - first);
	in->tail = (in->tail + n) % RING_BYTES;
	in->count -= n;
}

/* What says an utterance is over, which took three goes to get right.
 *
 * eciSpeaking cannot be it. It answers whether anything is outstanding right
 * now, and a reader that drains the ring faster than the engine fills it sees
 * nought between two of the engine's own messages and calls an utterance
 * finished part way through.
 *
 * eciSynchronize alone cannot be it either. eciSynthesize only queues the work
 * and answers; asked before the synthesis thread has taken the utterance up,
 * eciSynchronize finds nothing outstanding and answers at once, and then the
 * utterance is over before it began. That is not a rare window -- it cost a
 * whole utterance about one time in four.
 *
 * So the wait is in two parts: first until the engine has visibly taken the
 * work, then eciSynchronize, which is exact once there is something for it to
 * wait on. Having taken it shows either as a buffer already delivered or as
 * eciSpeaking answering yes. Text that yields no audio at all shows as
 * neither, which is what the spin limit is for; it is the only case that waits
 * it out, and it waits a fifth of a second rather than for ever.
 *
 * It has to be its own thread because the thread that reads cannot block.
 *
 * It is one thread for the life of the instance rather than one per utterance,
 * and that is not tidiness. The engine has no thread of its own: asking it
 * anything is what makes it run, so whichever thread calls eciSynchronize is
 * the thread the engine runs on, and it took the callback with it. Handing it
 * a thread made fresh each time faults after about fifty-five utterances, on
 * the phone and in a plain C harness alike, with the machine jumping into its
 * own data. The same loop on one kept thread runs for hundreds. */
static void* worker_loop(void* data) {
	instance* in = (instance*)data;
	struct timespec tick = { 0, 1000L * 1000L };
	for (;;) {
		int spins;
		pthread_mutex_lock(&in->lock);
		while (!in->pending && !in->quitting)
			pthread_cond_wait(&in->work, &in->lock);
		if (in->quitting) {
			pthread_mutex_unlock(&in->lock);
			return NULL;
		}
		in->pending = 0;
		pthread_mutex_unlock(&in->lock);
		for (spins = 0; spins < START_SPINS; spins++) {
			int begun;
			pthread_mutex_lock(&in->lock);
			begun = in->started || in->aborted;
			pthread_mutex_unlock(&in->lock);
			if (begun || eciSpeaking(in->eci))
				break;
			nanosleep(&tick, NULL);
		}
		eciSynchronize(in->eci);
		pthread_mutex_lock(&in->lock);
		in->done = 1;
		in->busy = 0;
		pthread_cond_broadcast(&in->filled);
		pthread_cond_broadcast(&in->work);
		pthread_mutex_unlock(&in->lock);
	}
}

/* Marks an utterance over that never started, so that a reader waiting on the
   end condition is not waiting for work nothing handed over. */
static jboolean finished(instance* in) {
	pthread_mutex_lock(&in->lock);
	in->done = 1;
	in->busy = 0;
	pthread_cond_broadcast(&in->filled);
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
	return JNI_FALSE;
}

/* Runs on the engine's synthesis thread. It may not call back into the same
   instance, and it does not: everything it touches is ours. */
static int ECICALL on_message(ECIHand handle, ECIMessage message, int param, void* data) {
	instance* in = (instance*)data;
	size_t want;
	(void)handle;
	if (message != eciWaveformBuffer)
		return eciDataProcessed;
	want = (size_t)param * sizeof(short);
	if (want == 0)
		return eciDataProcessed;
	pthread_mutex_lock(&in->lock);
	while (!in->aborted && RING_BYTES - in->count < want)
		pthread_cond_wait(&in->room, &in->lock);
	if (in->aborted) {
		pthread_mutex_unlock(&in->lock);
		return eciDataAbort;
	}
	put(in, (const unsigned char*)in->frame, want);
	in->started = 1;
	pthread_cond_signal(&in->filled);
	pthread_mutex_unlock(&in->lock);
	return eciDataProcessed;
}

JNIEXPORT jlong JNICALL Java_org_evvdroid_EvvNative_create(JNIEnv* env, jclass cls, jint language) {
	instance* in;
	(void)env;
	(void)cls;
	/* Keep OpenEVV's default sample-rate pipeline. At 16 kHz the engine uses
	 * its direct native synthesizer; above 16 kHz it automatically enables
	 * the 22.05 kHz wideband companion, then its default sinc converter raises
	 * the combined stream to the requested output rate. EVV_UPSAMPLE=none
	 * bypasses that path and disables the companion; it is an experiment
	 * switch, not the Android output mode. Clear any inherited override before
	 * the first engine opens, since the native rate policy is cached process-wide. */
	unsetenv("EVV_UPSAMPLE");
	/* The heteronym filter stays on: its forced readings are written in
	 * the words' place when they match and honoured literally, stress
	 * marks and all -- measured in the oracle, where persona answers
	 * [.0pXr.1so.0nx] mark for mark. Only whole words ever match, so an
	 * inflection keeps its own ending for the rules. Read once per
	 * process, beside the above. A refusal is not fatal. */
	setenv("EVV_HETERO", "on", 1);
	in = (instance*)calloc(1, sizeof *in);
	if (in == NULL)
		return 0;
	in->ring = (unsigned char*)malloc(RING_BYTES);
	if (in->ring == NULL) {
		free(in);
		return 0;
	}
	in->eci = eciNewEx(language);
	if (in->eci == NULL_ECI_HAND) {
		LOGE("eciNewEx(%#x) gave no instance", language);
		free(in->ring);
		free(in);
		return 0;
	}
	pthread_mutex_init(&in->lock, NULL);
	pthread_cond_init(&in->room, NULL);
	pthread_cond_init(&in->filled, NULL);
	pthread_cond_init(&in->work, NULL);
	eciRegisterCallback(in->eci, on_message, in);
	if (!eciSetOutputBuffer(in->eci, FRAME_SAMPLES, in->frame)) {
		LOGE("the engine refused the output buffer");
		eciDelete(in->eci);
		free(in->ring);
		free(in);
		return 0;
	}
	if (pthread_create(&in->worker, NULL, worker_loop, in) != 0) {
		LOGE("no thread to run the engine on");
		eciDelete(in->eci);
		free(in->ring);
		free(in);
		return 0;
	}
	in->running = 1;
	return (jlong)(intptr_t)in;
}

JNIEXPORT void JNICALL Java_org_evvdroid_EvvNative_destroy(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return;
	pthread_mutex_lock(&in->lock);
	in->aborted = 1;
	in->quitting = 1;
	pthread_cond_broadcast(&in->room);
	pthread_cond_broadcast(&in->filled);
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
	/* The abort flag is what brings the worker out of the engine: the next
	   buffer it is offered is answered eciDataAbort and eciSynchronize
	   returns. Nothing is asked of the engine from this thread. */
	if (in->running) {
		pthread_join(in->worker, NULL);
		in->running = 0;
	}
	pthread_mutex_lock(&in->lock);
	while (in->dictionary_leases > 0)
		pthread_cond_wait(&in->work, &in->lock);
	pthread_mutex_unlock(&in->lock);
	if (in->dict != NULL_DICT_HAND)
		eciDeleteDict(in->eci, in->dict);
	eciDelete(in->eci);
	pthread_cond_destroy(&in->room);
	pthread_cond_destroy(&in->filled);
	pthread_cond_destroy(&in->work);
	pthread_mutex_destroy(&in->lock);
	free(in->text);
	free(in->ring);
	free(in);
}

/* Hands the text over and starts the engine. It answers at once; the samples
   come out of read below.
 *
 * The text is bytes in the language's own code set rather than UTF-16, which
 * the engine allows only for Chinese, Japanese and Korean; Kotlin has already
 * turned it into that set. It is kept until the next utterance because nothing
 * here knows whether the engine copied it. */
JNIEXPORT jboolean JNICALL Java_org_evvdroid_EvvNative_speak(JNIEnv* env, jclass cls, jlong handle, jbyteArray text, jint native_speed_permille) {
	instance*      in = unwrap(handle);
	jsize          n;
	unsigned char* buf;
	(void)cls;
	if (in == NULL || text == NULL)
		return JNI_FALSE;
	n = (*env)->GetArrayLength(env, text);
	buf = (unsigned char*)malloc((size_t)n + 1);
	if (buf == NULL)
		return JNI_FALSE;
	(*env)->GetByteArrayRegion(env, text, 0, n, (jbyte*)buf);
	buf[n] = 0;
	pthread_mutex_lock(&in->lock);
	while ((in->busy || in->dictionary_replacing) && !in->quitting)
		pthread_cond_wait(&in->work, &in->lock);
	if (in->quitting) {
		pthread_mutex_unlock(&in->lock);
		free(buf);
		return JNI_FALSE;
	}
	in->aborted = 0;
	in->started = 0;
	in->done = 0;
	in->busy = 1;
	in->head = in->tail = in->count = 0;
	pthread_mutex_unlock(&in->lock);
	free(in->text);
	in->text = buf;
	if (!in->running) {
		LOGE("there is no worker to run the engine");
		return finished(in);
	}
	if (native_speed_permille < 1 || native_speed_permille > 60000) {
		LOGE("invalid native speed scale %d", native_speed_permille);
		return finished(in);
	}
	if (eciSetParam(in->eci, eciNativeSpeedScale, native_speed_permille) < 0) {
		LOGE("the engine refused native speed scale %d", native_speed_permille);
		return finished(in);
	}
	if (!eciAddText(in->eci, buf)) {
		LOGE("eciAddText refused the text");
		return finished(in);
	}
	if (!eciSynthesize(in->eci)) {
		LOGE("eciSynthesize refused");
		return finished(in);
	}
	pthread_mutex_lock(&in->lock);
	in->pending = 1;
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
	return JNI_TRUE;
}

/* Bytes out, blocking until there are some. Answers 0 at the end of the
   utterance and -1 when it was stopped. */
JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_read(JNIEnv* env, jclass cls, jlong handle, jbyteArray dst) {
	instance*      in = unwrap(handle);
	size_t         room;
	size_t         got;
	unsigned char* scratch;
	(void)cls;
	if (in == NULL || dst == NULL)
		return -1;
	room = (size_t)(*env)->GetArrayLength(env, dst);
	if (room == 0)
		return -1;
	pthread_mutex_lock(&in->lock);
	while (in->count == 0 && !in->done && !in->aborted)
		pthread_cond_wait(&in->filled, &in->lock);
	if (in->aborted) {
		pthread_mutex_unlock(&in->lock);
		return -1;
	}
	if (in->count == 0) {
		pthread_mutex_unlock(&in->lock);
		return 0;
	}
	got = in->count < room ? in->count : room;
	scratch = (unsigned char*)malloc(got);
	if (scratch == NULL) {
		pthread_mutex_unlock(&in->lock);
		return -1;
	}
	take(in, scratch, got);
	pthread_cond_signal(&in->room);
	pthread_mutex_unlock(&in->lock);
	(*env)->SetByteArrayRegion(env, dst, 0, (jsize)got, (const jbyte*)scratch);
	free(scratch);
	return (jint)got;
}

/* Wakes the callback before asking the engine to stop, since the callback may
   be waiting for room that a stopped reader will never make. */
JNIEXPORT void JNICALL Java_org_evvdroid_EvvNative_stop(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return;
	pthread_mutex_lock(&in->lock);
	while (in->dictionary_replacing && !in->quitting)
		pthread_cond_wait(&in->work, &in->lock);
	in->aborted = 1;
	in->head = in->tail = in->count = 0;
	pthread_cond_broadcast(&in->room);
	pthread_cond_broadcast(&in->filled);
	pthread_mutex_unlock(&in->lock);
	/* And the engine's own queue goes too, or the tail of the stopped
	 * utterance is still in it and speaks first inside the next one --
	 * the tiny stale chunk riding the next swipe. eciStop clears the
	 * romanizer and engine queues and resets the counts; calling it on
	 * an idle engine wedged it once, and upstream fixed that, so it is
	 * safe on every path here. Buffers still in flight answer
	 * eciDataAbort off the flag above and never reach the ring. */
	eciStop(in->eci);
}

/* An utterance holds a lease for the whole time its words may be read. A
   replacement marks itself in progress before building a new set, so no new
   lease or utterance can enter until publication has finished. */
JNIEXPORT jboolean JNICALL Java_org_evvdroid_EvvNative_acquireDictionaryLease(
    JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return JNI_FALSE;
	pthread_mutex_lock(&in->lock);
	while (in->dictionary_replacing && !in->quitting)
		pthread_cond_wait(&in->work, &in->lock);
	if (in->quitting) {
		pthread_mutex_unlock(&in->lock);
		return JNI_FALSE;
	}
	in->dictionary_leases++;
	pthread_mutex_unlock(&in->lock);
	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_org_evvdroid_EvvNative_releaseDictionaryLease(
    JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return;
	pthread_mutex_lock(&in->lock);
	if (in->dictionary_leases > 0)
		in->dictionary_leases--;
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
}

/* Load a complete immutable volume with OpenEVV's own bulk loader.
   eciLoadDict reaches eciLoadDictVolume inside the engine, so a Root volume
   with tens of thousands of entries is parsed as one native operation rather
   than thousands of public eciUpdateDict calls from the JNI bridge. */
static long long monotonic_millis(void) {
	struct timespec now;
	if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
		return -1;
	return (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

static int nonempty_regular_file(const char* path) {
	struct stat info;
	return path != NULL && stat(path, &info) == 0 &&
		S_ISREG(info.st_mode) && info.st_size > 0;
}

static int load_dictionary_volume(ECIHand eci, ECIDictHand dict,
                                  int volume, const char* path) {
	long long started = monotonic_millis();
	int answer = eciLoadDict(eci, dict, volume, path);
	long long finished = monotonic_millis();
	long long elapsed_ms = started >= 0 && finished >= started
		? finished - started : -1;
	if (answer == 0)
		LOGI("dictionary volume %d loaded natively in %lld ms: %s",
			volume, elapsed_ms, path);
	else
		LOGE("native loader refused dictionary volume %d (%d): %s",
			volume, answer, path);
	return answer;
}

/* Text snapshots are immutable and content-addressed, but the ECI engine
   still has to parse every line when a new engine instance is created. Save
   the engine's own binary volume beside that snapshot after the first load.
   It is scoped to the snapshot directory, so a source change naturally gets
   a different cache path and stale caches retire with their snapshot. */
static int load_dictionary_volume_cached(ECIHand eci, ECIDictHand dict,
                                         int volume, const char* source_path) {
	static const char suffix[] = ".evv-native";
	static const char temporary_suffix[] = ".evv-native.tmp";
	size_t length = strlen(source_path);
	char* cache_path = (char*)malloc(length + sizeof(suffix));
	char* temporary_path = (char*)malloc(length + sizeof(temporary_suffix));
	int answer = -1;
	if (cache_path == NULL || temporary_path == NULL)
		goto done;
	snprintf(cache_path, length + sizeof(suffix), "%s%s", source_path, suffix);
	snprintf(temporary_path, length + sizeof(temporary_suffix), "%s%s",
		source_path, temporary_suffix);

	if (nonempty_regular_file(cache_path)) {
		answer = load_dictionary_volume(eci, dict, volume, cache_path);
		if (answer == 0) {
			LOGI("dictionary volume %d used its compiled native cache", volume);
			goto done;
		}
		LOGE("discarding rejected native dictionary cache for volume %d", volume);
		unlink(cache_path);
	}

	unlink(temporary_path);
	answer = load_dictionary_volume(eci, dict, volume, source_path);
	if (answer == 0) {
		int saved = eciSaveDict(eci, dict, volume, temporary_path);
		if (saved == 0 && nonempty_regular_file(temporary_path)) {
			int fd = open(temporary_path, O_RDONLY);
			if (fd >= 0) {
				fsync(fd);
				close(fd);
			}
			chmod(temporary_path, S_IRUSR);
			if (rename(temporary_path, cache_path) == 0) {
				LOGI("compiled native dictionary cache for volume %d: %s",
					volume, cache_path);
			} else {
				LOGE("could not publish native dictionary cache for volume %d",
					volume);
				unlink(temporary_path);
			}
		} else {
			LOGE("could not compile native dictionary cache for volume %d (code %d)",
				volume, saved);
			unlink(temporary_path);
		}
	}

done:
	free(cache_path);
	free(temporary_path);
	return answer;
}

/* Build all three volumes in a detached ECI dictionary handle. Only after
   every selected file loads do we activate the candidate and retire the old
   handle. A bad volume therefore cannot leave a half-updated dictionary set. */
JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_replaceDictionaries(
    JNIEnv* env, jclass cls, jlong handle, jobjectArray paths) {
	instance* in = unwrap(handle);
	char* names[3] = { NULL, NULL, NULL };
	ECIDictHand candidate = NULL_DICT_HAND;
	ECIDictHand previous;
	int any = 0;
	int answer = -1;
	jsize count;
	(void)cls;
	if (in == NULL || paths == NULL)
		return -1;
	count = (*env)->GetArrayLength(env, paths);
	if (count != 3)
		return -1;
	for (jsize i = 0; i < count; i++) {
		jstring path = (jstring)(*env)->GetObjectArrayElement(env, paths, i);
		if (path != NULL) {
			const char* text = (*env)->GetStringUTFChars(env, path, NULL);
			if (text == NULL) {
				(*env)->DeleteLocalRef(env, path);
				goto cleanup;
			}
			names[i] = strdup(text);
			(*env)->ReleaseStringUTFChars(env, path, text);
			(*env)->DeleteLocalRef(env, path);
			if (names[i] == NULL)
				goto cleanup;
			any = 1;
		} else {
			(*env)->DeleteLocalRef(env, path);
		}
	}

	pthread_mutex_lock(&in->lock);
	while ((in->busy || in->dictionary_leases > 0 || in->dictionary_replacing)
			&& !in->quitting)
		pthread_cond_wait(&in->work, &in->lock);
	if (in->quitting) {
		pthread_mutex_unlock(&in->lock);
		goto cleanup;
	}
	in->dictionary_replacing = 1;
	pthread_mutex_unlock(&in->lock);

	if (any) {
		candidate = eciNewDict(in->eci);
		if (candidate == NULL_DICT_HAND) {
			LOGE("the engine would not create a replacement dictionary set");
			goto publish_done;
		}
		for (int volume = 0; volume < 3; volume++) {
			if (names[volume] == NULL)
				continue;
			int rc = load_dictionary_volume_cached(in->eci, candidate, volume, names[volume]);
			if (rc != 0) {
				LOGE("dictionary volume %d refused %s: %d", volume, names[volume], rc);
				goto publish_done;
			}
		}
	}
	if (eciSetDict(in->eci, candidate) != 0) {
		LOGE("the engine would not activate the replacement dictionary set");
		goto publish_done;
	}
	previous = in->dict;
	in->dict = candidate;
	candidate = NULL_DICT_HAND;
	if (previous != NULL_DICT_HAND)
		eciDeleteDict(in->eci, previous);
	answer = 0;

publish_done:
	if (candidate != NULL_DICT_HAND)
		eciDeleteDict(in->eci, candidate);
	pthread_mutex_lock(&in->lock);
	in->dictionary_replacing = 0;
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);

cleanup:
	for (int i = 0; i < 3; i++)
		free(names[i]);
	return answer;
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_setParam(JNIEnv* env, jclass cls, jlong handle, jint param, jint value) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciSetParam(in->eci, param, value);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_getParam(JNIEnv* env, jclass cls, jlong handle, jint param) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciGetParam(in->eci, param);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_setVoiceParam(JNIEnv* env, jclass cls, jlong handle, jint voice, jint param, jint value) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciSetVoiceParam(in->eci, voice, param, value);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_getVoiceParam(JNIEnv* env, jclass cls, jlong handle, jint voice, jint param) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciGetVoiceParam(in->eci, voice, param);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_copyVoice(JNIEnv* env, jclass cls, jlong handle, jint from, jint to) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciCopyVoice(in->eci, from, to);
}

JNIEXPORT jintArray JNICALL Java_org_evvdroid_EvvNative_languages(JNIEnv* env, jclass cls) {
	unsigned int found[32];
	int          n = 0;
	jintArray    out;
	(void)cls;
	if (eciGetAvailableLanguages(NULL, &n) != 0 || n < 1)
		return (*env)->NewIntArray(env, 0);
	if (n > 32)
		n = 32;
	if (eciGetAvailableLanguages(found, &n) != 0)
		return (*env)->NewIntArray(env, 0);
	out = (*env)->NewIntArray(env, n);
	if (out == NULL)
		return NULL;
	(*env)->SetIntArrayRegion(env, out, 0, n, (const jint*)found);
	return out;
}

JNIEXPORT jstring JNICALL Java_org_evvdroid_EvvNative_version(JNIEnv* env, jclass cls) {
	char buffer[ECI_VERSION_LENGTH];
	(void)cls;
	memset(buffer, 0, sizeof buffer);
	eciVersion(buffer);
	buffer[sizeof buffer - 1] = 0;
	return (*env)->NewStringUTF(env, buffer);
}
