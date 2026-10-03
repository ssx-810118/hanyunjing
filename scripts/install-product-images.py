from pathlib import Path
from PIL import Image, ImageOps

source = Path('output/imagegen/catalogue')
product = Path('src/main/resources/static/images/products')
garment = Path('src/main/resources/static/images/tryon-garments')
for item in sorted(source.glob('p*.png')):
    if item.stem not in {'p15','p16','p18','p19','p20','p21','p22','p23','p24','p25','p26'}:
        continue
    image = Image.open(item).convert('RGB')
    display = ImageOps.pad(image, (768,1024), color='#ede2cc')
    display.save(product / f'{item.stem}.webp', quality=88, method=6)
    image.thumbnail((1024,1536))
    image.save(garment / f'{item.stem}.jpg', quality=92)
    print(item.stem, 'installed; source', Image.open(item).size)
