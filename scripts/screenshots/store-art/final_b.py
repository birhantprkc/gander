import sys; sys.path.insert(0,'.')
from compose_b import build, win
blk=lambda x,y,w,h:f'<div class="blk" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px"></div>'
P=[
 # 1  permissions leads. The whole About dialog, so the frame carries real content
 #    instead of dead black under a short card.
 {"cap":("Takes","nothing."), "kick":"No permissions. No trackers.<br>No internet access at all.",
  "parts":[blk(60,868,1053,1052), win("soft",8,818,1053,1102,"d-about.png",150,758,1.35)]},
 {"cap":("Opens","everything."), "kick":"PDF, Word, Excel, PowerPoint, photos,<br>video, audio, Markdown and code.",
  "parts":[win("",0,880,1080,1040,"d-welcome.png",270,596,2.0)]},
 {"cap":("Finds","anything."), "kick":"Search inside a PDF, a spreadsheet<br>or a deck. Match by match.",
  "parts":[blk(72,905,1000,165),
           win("soft",40,875,1000,152,"d-find.png",18,272,0.95),
           win("soft ring",40,1100,1000,740,"d-find.png",20,470,0.95)]},
 {"cap":("Every","sheet."), "kick":"Multi-sheet workbooks, tabs and all.<br>xlsx, xls, xlsm, xlsb, csv, ods.",
  "parts":[blk(72,905,1000,760), win("soft",40,875,1000,750,"d-xlsx.png",20,150,0.95)]},
 {"cap":("Any","deck."), "kick":"PowerPoint slides, without<br>installing an office suite.",
  "parts":[blk(72,905,1000,760), win("soft",40,875,1000,750,"d-pptx.png",20,150,0.95)]},
 # 6  a viewer that actually goes dark - the Markdown reader, not a PDF page
 {"cap":("Reads","at 2am."), "kick":"Follows your phone into dark mode,<br>everywhere in the app.",
  "parts":[blk(72,905,1000,940), win("soft",40,875,1000,930,"d-md.png",20,150,0.95)]},
]
print(build(P,"b.html"),"panels")
