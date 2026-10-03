"""Save public textual source evidence only; never download third-party images."""
from pathlib import Path
import concurrent.futures, json, re, sys, urllib.request, urllib.parse
from html.parser import HTMLParser
sys.stdout.reconfigure(encoding='utf-8')
class Text(HTMLParser):
 def __init__(self):super().__init__();self.parts=[];self.skip=0;self.links=[]
 def handle_starttag(self,tag,attrs):
  if tag in ['script','style']:self.skip+=1
  if tag=='a':
   href=dict(attrs).get('href','')
   if href:self.links.append(href)
 def handle_endtag(self,tag):
  if tag in ['script','style']:self.skip=max(0,self.skip-1)
 def handle_data(self,data):
  if not self.skip and data.strip():self.parts.append(data.strip())
root=Path('docs/history-evidence');root.mkdir(parents=True,exist_ok=True)
def fetch(entry):
 name,url=entry
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=25) as response:raw=response.read().decode('utf-8',errors='replace')
  parser=Text();parser.feed(raw);body='\n'.join(parser.parts)
  (root/(name+'.txt')).write_text('URL: '+url+'\n'+body,encoding='utf-8')
  (root/(name+'-links.json')).write_text(json.dumps(list(dict.fromkeys(parser.links)),ensure_ascii=False),encoding='utf-8')
  print(json.dumps({'source':name,'url':url,'length':len(body),'excerpts':[body[max(0,m.start()-60):m.start()+260] for m in list(re.finditer('比甲|答忽|搭護|半袖|抹胸|背心|短衫|襦|襴衫|袴|素纱|盤領',body))[:8]]},ensure_ascii=False))
 except Exception as e:print(json.dumps({'source':name,'error':str(e)},ensure_ascii=False))
items=[('song-shi-153','https://zh.wikisource.org/wiki/'+urllib.parse.quote('宋史/卷153')),('yuan-shi-078','https://zh.wikisource.org/wiki/'+urllib.parse.quote('元史/卷078')),('yuefu-28','https://zh.wikisource.org/wiki/'+urllib.parse.quote('樂府詩集/卷028')),('song-google','https://www.google.com/search?q='+urllib.parse.quote('黄昇墓 裤 福建 博物院')),('yuan-dahu-google','https://www.google.com/search?q='+urllib.parse.quote('元代 答忽 半袖 博物馆'))]
if len(sys.argv)>2:items=[(sys.argv[1],sys.argv[2])]
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:list(pool.map(fetch,items))
