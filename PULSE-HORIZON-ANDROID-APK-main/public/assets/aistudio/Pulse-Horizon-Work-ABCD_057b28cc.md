PULSE HORIZON - WORK A, B, C, D
===============================

Everything still to do, in four groups. Hand this to Studio as the brief.

Read the rules first. They matter more than any single item.


============================================================
RULES FOR WHOEVER DOES THIS
============================================================

1. DO NOT CHANGE ANYTHING THAT ALREADY WORKS.

   These work. They must behave identically when you are finished:

     tabs: creating, closing, switching, the tab strip, the tab count
     the home page, the shortcuts, the real site icons
     the first-run terms screen
     bookmarks
     ad and tracker blocking, the block and allow lists, the blocked counter
     theme: dark, light, follow-system
     the media notification and its play / pause / skip buttons
     the QR scanner opening, previewing, scanning
     downloads, the file picker, share, copy link
     the address bar, the menu, Settings
     the offline page, the home button
     background music continuing when you leave the app
     the edge glow

   If a fix would change any of those, STOP and say so.

2. THE REPOSITORY IS THE ONLY SOURCE OF TRUTH.

   Do not work in a private copy and let it drift. Every change must end up in
   the repo, and the APK that gets tested must be built from the repo. A
   mismatched copy has already cost a full day of testing the wrong app.

3. STATE THE CAUSE BEFORE THE FIX.

   For each item, say in one or two sentences what is actually wrong. If you
   cannot find the cause, say so and stop on that item. Do not change things at
   random to see what happens.

4. ONE GROUP AT A TIME. Compile after each. Say which files changed, with full
   paths.


============================================================
A - THE FIVE CRASHES
============================================================

A1. THE QR SCANNER SHOWS A BLACK SCREEN
    Cause: the scanner calls decodeContinuous() when it opens. That sets up the
    DECODER. It does not start the camera. resume() starts the camera, and it is
    never called on open - the only resume() is inside "scan again after a
    freeze". So the camera never starts: black screen, with only the reticle and
    the hint drawn on top.
    Fix: resume() when the dialog starts, pause() when it stops - the library's
    own lifecycle. Also do not mark the dialog as closing in onStop, or
    backgrounding the app kills the scanner for good.

A2. CHANGING THE SYSTEM THEME WHILE THE APP IS OPEN CRASHES IT
    Cause: the manifest lists orientation, screenSize, screenLayout,
    keyboardHidden and keyboard in configChanges - but NOT uiMode. uiMode is what
    changes between light and dark. Because it is missing, Android does not tell
    the app; it DESTROYS and REBUILDS the activity. And onDestroy does a full
    cleanup and then Process.killProcess. So the rebuild kills the app.
    Fix: add uiMode to configChanges, and handle onConfigurationChanged by
    re-applying the theme in place. Only when light/dark ACTUALLY changed - not
    on every rotation, or the tabs rebuild and a toast pops on every rotate.

A3. TAPPING THE MEDIA NOTIFICATION OPENS AUDIO SETTINGS
    Cause: the notification's content intent is already correct. What is missing
    is a session activity on the MediaSession - and Android routes taps on a
    MEDIA notification through the session, not just the notification.
    Fix: set the session activity to the same intent that opens the browser.

A4. A LOCAL MUSIC FILE CRASHES THE APP WHEN IT GOES TO THE BACKGROUND
    Cause: a genuine use-after-free. musicStop() paused, flushed and nulled the
    AudioTrack while the decoder thread was STILL WRITING to it, and the thread's
    own finally was about to release() it. Two threads on one native object, one
    of them freeing it. It kills the process with no Java stack trace.
    It fires on leaving the app because onPause calls musicStop().
    Fix: each playback gets its OWN cancellation token. Stopping one cancels
    that one only. Only the decoder thread touches its own track. The thread's
    finally clears the shared reference only if it is still its own.
    NOTE: a shared "isPlaying" style flag does NOT work here - starting the next
    song sets it back to true and revives the stopped thread. That was tried and
    failed. Use a per-playback token.

A5. THE TAB-CLOSED UNDO
    Wanted: a small bar, the height of the address bar, for 5 seconds, with an
    UNDO button that brings the tab back and reopens it.
    Currently: a dialog in the middle of the screen for 10 seconds - and the
    bar has never actually appeared in recent builds.
    IMPORTANT: an earlier attempt inserted the bar into the browser's own layout
    tree at an index near the address bar, and that disturbed the tab UI. The
    bar must hang off the CONTENT view as an overlay, and must not touch the
    browser's layout at all.
    Also check WHY it never appears. If three different ways of drawing it all
    failed to show, the likely cause is that the code that is supposed to trigger
    it is not running. Check the trigger before redesigning the bar.


============================================================
B - TWO QUICK UI CHANGES
============================================================

B1. A BACK ARROW IN SETTINGS AND EVERY MENU
    Right now Settings and the other menus are dialogs with only a "Done"
    button. If you open the wrong one there is no way back.
    Wanted: a Back button in every menu that returns you to whatever you came
    from - Settings back to the menu, the menu back to the browser. Track where
    it was opened from, so Back means Back and not "close everything".

B2. MOVE "EDGE GLOW" AND "BACKGROUND MUSIC" INTO A MUSIC SECTION
    Both are music features sitting loose in the main Settings list.
    Wanted: one "Music" row in Settings that opens a Music screen containing
    Edge glow, Background music, and their switch-on notices. Everything else
    stays exactly where it is.


============================================================
C - REAL WEBSITE PREVIEWS ON TAB AND BOOKMARK CARDS
============================================================

WHAT IS THERE NOW - CONFIRMED BY READING THE CODE
    There is NO preview. The middle of a tab card is a placeholder: a globe
    icon and the URL text. The tab list the browser hands to the page carries
    only title, url and active - no image at all. The native tab switcher is a
    plain text list. The only images anywhere in the app are the shortcut
    favicons and the launcher icon.

SO THIS IS BUILDING IT, NOT FIXING IT

WANTED
    Each tab card shows a real thumbnail of the page that tab is showing.
    Each bookmark card shows a thumbnail too.

THE HARD PART - AND IT IS NOT OPTIONAL
    Memory must NOT increase. So the thumbnails must be bounded:
      - a fixed pixel size, small
      - a hard cap on how many are kept
      - stored as compressed bytes, not raw bitmaps
      - the oldest evicted when the cap is reached
      - dropped when a tab closes, and when the app goes to the background
    Unbounded thumbnails are exactly how memory balloons and the whole
    zero-background-memory design is defeated.

BOOKMARKS
    A bookmark has no page open, so there is nothing to capture. Those get the
    site's own icon instead of a thumbnail.


============================================================
D - STILL OPEN FROM EARLIER
============================================================

D1. THE TERMS CHECKBOX
    Wanted: an "I have read and agree to the Terms" checkbox, ticked BEFORE the
    permission request is made and BEFORE the home screen is shown.
    Order: terms page -> tick the box -> Submit -> permissions -> home.
    The terms themselves live in the terms page.

D2. THE BOOKMARKS REDESIGN
    A design mockup was made with two options - one flat list grouped by
    Today / Earlier, and one grouped by site - and a choice was never made.
    Either pick one, or drop this item. Do not redesign it blind.


============================================================
THE ORDER TO DO THEM IN
============================================================

  1. A  - the crashes. Nothing else matters while the app can die.
  2. B  - the two quick UI changes. Independent of each other and low risk.
  3. C  - the previews. Its own pass, because of the memory bound.
  4. D  - when there is time.

  A must be its own build. B and C can share one, because they do not touch
  each other.


============================================================
WHAT TO REPORT BACK
============================================================

  For each item: the cause found, what changed, in which file, and what you
  could not verify.

  And one line at the end naming anything from the working-features list that
  you are not certain still works.
