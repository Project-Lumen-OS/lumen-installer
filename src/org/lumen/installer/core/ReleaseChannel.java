package org.lumen.installer.core;

/** which build a download reads ("Version: Stable / Beta", remembered in the state file): the catalog's stable build of
 *  the model, or its newest beta when it has one. A model with a beta only has no choice: its beta is read whatever
 *  is stored. A stick folder's file names its own build. */
public enum ReleaseChannel { STABLE, BETA }
