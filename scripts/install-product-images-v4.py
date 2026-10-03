from pathlib import Path
from PIL import Image, ImageOps, ImageDraw
import shutil, sys
source=Path('output/imagegen/catalogue-v4')
ids=['p7','p15','p16','p18','p20','p21','p23','p25','p26']
sheet=Image.new('RGB',(1500,840),'#f7f4ed')
for n,id in enumerate(ids):
    image=Image.open(source/(id+'.png')).convert('RGB')
    assert image.width>=768 and image.height>=1024
    thumb=ImageOps.contain(image,(280,390))
    x=(n%5)*300+(300-thumb.width)//2;y=(n//5)*420
    sheet.paste(thumb,(x,y));ImageDraw.Draw(sheet).text(((n%5)*300+12,y+393),id,fill='black')
    if '--install' in sys.argv:
        for folder,ext in [('products','webp'),('tryon-garments','jpg')]:
            target=Path('src/main/resources/static/images')/folder/(id+'.'+ext)
            backup=source/'previous'/folder/target.name
            backup.parent.mkdir(parents=True,exist_ok=True)
            if target.exists() and not backup.exists():shutil.copy2(target,backup)
            if ext=='webp':ImageOps.pad(image,(768,1024),color='#ede2cc').save(target,quality=88,method=6)
            else:image.save(target,quality=92)
sheet.save(source/'contact-sheet.jpg',quality=92)
print('Verified',len(ids),'images;', 'installed with previous assets backed up.' if '--install' in sys.argv else 'contact sheet ready.')
