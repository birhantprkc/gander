# Changelog

## 1.13 (2026-08-25)

- Gander is a good deal smaller to download. Its own code is under 1,700 lines, but the
  Android UI libraries underneath it were being packaged whole whether or not anything
  called into them: 14.8 MB of compiled code in the 1.12 build, of which 2.4 MB is all
  the app can reach. R8 now runs over the release build and keeps the reachable part,
  and the same pass drops the resources nothing asks for. The APK goes from 8.3 MB to
  3.7 MB and the Play bundle from 7.6 MB to 5.1 MB. Nothing is missing from it: every
  screen, every format and every menu item is the one that shipped in 1.12. Class names
  are shortened on the way through, which buys no secrecy for an app whose source is
  public, but file names and line numbers are kept deliberately, so a crash pasted into
  an issue still points at a real line.

## 1.12 (2026-08-23)

- Large PDFs no longer bring the phone to its knees. Gander lays out an empty box for
  every page in a document before it draws any of them, so the scrollbar is the right
  length from the moment it opens, and until now those boxes had no size: each was
  300 by 150 pixels, a business card standing in for a page. That was visible as a
  column of small white rectangles whenever you scrolled faster than pages could be
  drawn, and it was the cause of a good deal more besides. Gander decides which pages
  are close enough to be worth drawing by looking a fixed distance ahead of you, and
  at that size about fifty of them fitted in the distance meant to hold three or four,
  so it started fifty at once, kept every one it had ever finished, and kept the
  artwork it decoded for them on top. On a 357-page illustrated rulebook, one flick
  through it took the renderer past 900 MB and Android killed it, sometimes taking
  other apps' browser windows down with it. The boxes are now the shape of the page
  they stand for, no more than three pages are ever drawn at once, a page you have
  flung past stops being drawn rather than finishing into a bitmap nobody will see,
  and a page far enough behind you gives back both its bitmap and its artwork. How far
  ahead Gander draws is now counted in pages rather than in screenfuls, which is the
  only version of that number that means the same thing when you turn the phone
  sideways. The rulebook now sits flat at about 180 MB however it is read, and a
  520-page, 114 MB ebook at about 300 MB, neither of them climbing
  (thanks @logannc, who found this and worked out that scrolling made it worse, and
  @mjschwart, who reproduced it on quite different hardware with a much heavier file)

## 1.11 (2026-08-21)

- A PDF that wants a password now asks you for it, rather than showing you the error
  its renderer raised. Type it and the document opens; get it wrong and it says so and
  lets you try again, as often as you like, without rereading the file. The prompt is
  drawn in the page rather than as an Android dialog, because Gander deliberately has
  no bridge between a document and the app in either direction and a password is not
  worth opening one for. Nothing is kept: it lives as long as the document is on screen
  and goes when you leave. A file encrypted in a way the renderer cannot read at all
  now says so in a sentence too, instead of printing the exception at you
  (thanks @juxuanu, who reported this and pointed at GrapheneOS's PdfViewer)

- The cards the PDF viewer puts on screen, including the one explaining that a phone's
  browser engine is too old for PDFs, were being drawn at about a third of the size
  they were meant to be. A PDF page is laid out at a fixed width and zoomed to fit,
  which is what keeps it sharp, and the cards had been quietly riding along with it.
  They are sized for that zoom now rather than against it.

- The file you are looking at can be kept, through **Save a copy** in the viewer menu.
  It is meant for the file that came from somewhere else: an attachment, or a document
  another app hands over, which Gander is allowed to read only for as long as that
  screen is open and which is out of reach once you leave. Where the copy goes is
  chosen in Android's own picker, so this asks for no permission and Gander is handed
  the one file it just wrote and nothing around it. A large copy reports its progress
  under the toolbar, and one that fails takes its half-written file with it rather than
  leaving something that opens and looks complete (thanks @sebestyn)

- A document left open no longer takes the whole app down with it when Android runs
  short of memory. Everything drawn by the sandboxed viewer, PDFs included, is
  rendered in a process of its own, and Android is free to end that process while
  Gander is in the background. Sharing a file to a large app is exactly that
  situation: the other app starts up in front, and the process holding the document
  is left sitting there with nobody looking at it. Gander had never answered that
  notification, and not answering it does not mean nothing happens, it means the
  framework kills the application, so the app disappeared with no crash of its own
  behind it and nothing on screen to account for it. It now stays open, says that
  Android closed the document to free up memory, and offers to load it again
  (thanks @rosyzc7 for the report that led here)

## 1.10 (2026-08-13)

- There is an About screen now, in the menu on the home screen. Rather than repeat
  a promise typed into it months earlier, it asks Android what permissions this
  install actually requests and shows you the answer. The build has always failed
  if a dependency slipped one in; this is the same check somewhere you can see it
- The full licence text for every bundled rendering library now ships inside the
  app and opens from About, drawn by Gander's own Markdown viewer. It was in the
  repository before, which is not the same as travelling with the app, and
  travelling with the app is what those licences ask for
- Gander no longer takes part in Android's app backup. The recents list was the
  only thing there was to copy, and a restored one is always empty anyway, because
  the folder permissions it points at do not survive being moved to another phone.
  So it was putting your file names into a Drive backup in exchange for nothing
- Phones whose WebView is too old for PDFs are recognised properly now. The check
  read the version off the WebView package, and a manufacturer that numbers its
  own builds 15.0.4.326 was read as version 15, dismissed as not a real version,
  and let through. Those phones then failed on the PDF with a parser error naming
  a file nobody has heard of. The version now comes from the browser engine itself,
  which reports it the same way no matter who makes the phone (thanks @sdiddssew)
- On a phone whose WebView cannot be updated, because the manufacturer supplies it
  and allows no replacement, the PDF message no longer tells you to go and update
  it. It says PDFs will not work on this phone and leads with the fact that every
  other format still opens, which is the part you can act on

## 1.9 (2026-08-06)

- Files Gander cannot render now offer a **View as text** button instead of a
  dead end, so a file with no extension can be read without renaming it to
  `.txt` first (thanks @immanuelfodor)
- The text viewer reads large files in 5 MB pages with a **Show more** button
  at the end, so a big log opens straight away instead of stalling while the
  whole thing loads, and none of it is out of reach
- Text files that start with a byte order mark, including UTF-16, now decode
  correctly instead of showing a stray character between every letter
- Gander now targets Android 16 (API 36), so it keeps working as newer
  releases tighten how apps draw behind the status and navigation bars
- The permission list is genuinely empty again. Media3 had been quietly
  adding ACCESS_NETWORK_STATE, which never did anything here because Gander
  only plays local files and has no internet access at all
- PDFs now render with pdf.js in the same sandboxed viewer that already handled
  Word and Excel, so the app ships no native code at all. The download is one
  APK for every phone instead of four, and about 8 MB instead of 15
- Very large PDFs are read a piece at a time as you scroll rather than loaded
  whole, so a 50 MB scan opens sooner and does not sit in memory while you read it
- The PDF viewer background now fills the screen behind a short document, the
  same fix the other viewers got in 1.8. It showed up most on a PDF that could
  not be opened, where the message sat on a dark band with a lighter one below
- PDFs open a little slower than before, by roughly half a second on a fast
  phone. The renderer that was faster cannot be shipped any more; it stopped
  being maintained and no longer meets current Play requirements
- The search button no longer appears for PDFs. PDF pages are drawn as images
  with no text behind them, so a search could only ever come back empty. Search
  in Word, Excel, slides, Markdown, text and code is unchanged
- On a phone whose Android System WebView is too old to run the PDF renderer,
  the viewer now says so and names the version needed, instead of sitting on
  "Rendering document…" with nothing to explain it. Updating Android System
  WebView fixes it, and no other format is affected

## 1.8 (2026-08-04)

- Short documents no longer show a grey band below the content. The viewer
  background now fills the screen in the Markdown, text, spreadsheet and
  slide views (thanks @lalalasupa0)

## 1.7 (2026-08-02)

- PDF zoom now reaches 10x instead of the previous 3x, enough to read a small
  QR code on a full page (thanks @neuos)
- OpenDocument spreadsheets (`.ods`) now appear in the Open-with list; they
  already rendered, but the MIME type was never registered (thanks to sgc on
  Hacker News)
- Screen reader support: the back button, the photo viewer, web-rendered
  images and the file rows on the home screen are all labelled now, and the
  search match count is announced as "Match 2 of 7" rather than "two sevenths"
  (thanks @freedomben for asking)

## 1.6 (2026-07-23)

- Share the open file to any app straight from the viewer toolbar
- "Show in file manager" opens the file's folder in the system Files app
  (appears when the folder can be worked out from where the file came from)

## 1.5 (2026-07-21)

- Find in document: search inside Word, Excel, PowerPoint, Markdown, text
  and code files with match count and next/previous navigation
  (PDF search is not included yet; the PDF renderer does not expose text)

## 1.4 (2026-07-19)

- Gander now appears in the Share sheet: share a document, photo, video or
  audio file from any app (WhatsApp, Gmail, a browser) straight into Gander
- Shared plain text opens in the text viewer

## 1.3.1 (2026-07-19)

- New app icon: fanned file cards on paper, matching the project artwork
  (the old eye mark read as surveillance, the opposite of what Gander is)

## 1.3 (2026-07-19)

- Thumbnail previews in Recent files and folder browsing: images (EXIF
  corrected), video frames, and PDF first pages, cached in memory and on disk

## 1.2 (2026-07-19)

- Useful home screen: Recent files (tap to reopen, long-press to remove) and
  Folders granted once via the system picker, browsable in-app with type
  badges, sizes and dates, all with zero permissions
- Note: Android refuses folder grants for the Downloads root; grant Documents,
  DCIM or a Downloads subfolder instead

## 1.1 (2026-07-19)

- Fixed toolbar sitting under display cutouts (edge-to-edge insets)
- Fixed sideways photos: EXIF orientation is now applied
- Markdown files render as formatted HTML (offline, sanitized)
- Video and audio playback via Media3 ExoPlayer, plus video/audio Open-with

## 1.0 (2026-07-19)

- First release as Gander (formerly ViewAll)
- PDF, Word (.docx), Excel (.xlsx .xls .csv .ods), PowerPoint (.pptx), photos,
  GIF/SVG, Markdown/text/code viewing, fully offline with zero permissions
- Per-ABI release APKs
