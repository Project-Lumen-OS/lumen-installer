package org.lumen.installer.core;

/** which release a download reads ("Version: Stable / Beta", remembered in the state file): the model's stable
 *  channel, or its beta channel when its entry has one (Index.Entry.asBeta). An entry with a beta only has no choice:
 *  its beta is read whatever is stored. Beta never applies to a stick folder. */
public enum ReleaseChannel { STABLE, BETA }
