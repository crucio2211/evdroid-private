# Keep user-supplied ECI phoneme dictionaries when NVDA replaces this add-on.
#
# NVDA stages an update in a sibling *pendingInstall folder before replacing
# the installed add-on. Copy recognised dictionary files from the current
# synthDrivers directory into that staged directory before the old one goes.
# Never move or overwrite a file; the new package's copy, if any, wins.

import os
import re
import shutil

import addonHandler
import globalVars
from logHandler import log

NAME = "openevv"

_DICTIONARY = re.compile(
    r"^(enu|eng|esp|esm|fra|frc|deu|ita|ptb|jpn|fin|pol)(main|root|abbr|mainext)\.dic$",
    re.IGNORECASE,
)


def preserveDictionaries(old, new):
    """Copy recognised dictionary files from old to new, without overwriting."""
    copied = []
    if not os.path.isdir(old) or not os.path.isdir(new):
        return copied
    if os.path.abspath(old) == os.path.abspath(new):
        return copied

    present = {name.lower() for name in os.listdir(new)}
    for name in os.listdir(old):
        if not _DICTIONARY.match(name) or name.lower() in present:
            continue
        source = os.path.join(old, name)
        if not os.path.isfile(source):
            continue
        try:
            shutil.copy2(source, os.path.join(new, name))
            copied.append(name)
        except OSError:
            log.warning("openevv: could not preserve dictionary %s" % name, exc_info=True)
    return copied


def onInstall():
    try:
        addons = os.path.join(globalVars.appArgs.configPath, "addons")
        old = os.path.join(addons, NAME, "synthDrivers")
        new = os.path.join(
            addons,
            NAME + addonHandler.ADDON_PENDINGINSTALL_SUFFIX,
            "synthDrivers",
        )
        copied = preserveDictionaries(old, new)
        if copied:
            log.info("openevv: preserved external dictionaries: %s" % ", ".join(copied))
    except Exception:  # noqa: BLE001
        # A failed preservation must not prevent NVDA from installing the add-on.
        log.warning("openevv: could not preserve external dictionaries", exc_info=True)
