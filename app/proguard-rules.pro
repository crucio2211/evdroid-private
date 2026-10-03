# The JNI bridge finds these by name.
-keepclasseswithmembernames class org.evvdroid.EvvNative {
	native <methods>;
}
# Apple-engine path: prebuilt libvvtts_core.so looks these up by name.
-keepclasseswithmembernames class com.xw.vvtts.core.VvttsCore {
	native <methods>;
}
-keep class com.xw.vvtts.utils.KonaVoice { *; }
