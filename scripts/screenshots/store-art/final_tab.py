import sys; sys.path.insert(0,'.')
from tab import build, slate, sweep, drift, fragment
bands = (drift(7, 16, 1150, 120, 3000, 70, 118, .93, .86, z=1, blur=2.4)
         + sweep(1330, 110, 4200, 0.4, z=4)
         + drift(23, 11, 1420, 110, 4200, 118, 176, .80, .58, z=6, accents=(5,))
         + drift(91, 7, 1520, 100, 4200, 196, 262, .58, .34, z=9, accents=(3,)))
P=[
 # 1  device bleeds off the right; the tile grid lifted over its left edge
 {"cap":("Opens","everything."),"kick":"Nine kinds of file, and nothing<br>uploaded to open any of them.",
  "bands":bands, "head_x":140, "head_y":420,
  "parts": slate(1210, 210, 1720, "t-welcome.png", rot=-1.2)
           + fragment(940, 900, 700, "t-welcome.png", 928, 440, 704, 590, rot=-2.4)},
 # 2  slate pushed right so it stops clipping the kicker; fragment low-left
 {"cap":("Room to","spread out."),"kick":"Two columns on a large screen.",
  "bands":bands, "head_x":140, "head_y":300,
  "parts": slate(1010, 560, 1520, "t-folder.png", rot=-1.0)
           + fragment(170, 1000, 920, "t-folder.png", 28, 186, 1180, 350, rot=-2.0)},
 # 3  mirrored: caption right, device bleeding off the left
 {"cap":("Reads","anything."),"kick":"Every document renders on the device.<br>No account, no upload, no cloud.",
  "bands":bands, "head_x":1480, "head_y":430,
  "parts": slate(-260, 250, 1660, "t-pdf.png", rot=1.1)},
 # 4  no fragment: the tab strip is legible at this size, and lifting it beside
 #    itself read as a duplicate rather than a magnification
 {"cap":("Every","sheet."),"kick":"Multi-sheet workbooks, tabs and all.<br>xlsx, xls, xlsm, xlsb, csv, ods.",
  "bands":bands, "head_x":140, "head_y":420,
  "parts": slate(1090, 300, 1820, "t-xlsx.png", rot=-1.2)},
]
print(build(P,"tab.html"),"tablet panels")
