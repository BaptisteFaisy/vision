// Carte existante, partagée entre le navigateur et la WebView Android.
import { destPoint, objectsOf } from './session.mjs';
const HOME={lat:48.8566,lon:2.3522};
const DETAIL_ZOOM=20;
function mapReady(){
 document.getElementById('map').setAttribute('aria-busy','false');
 document.getElementById('map-loading').hidden=true;
 document.getElementById('map').dataset.provider=mode;
 document.getElementById('map-provider').textContent=mode==='google'?'Google Maps actif':'Carte OpenFreeMap';
}
let live=null,lastPos=null,status='offline';
function state(){return status;}
function currentObjects(){return objectsOf(live,status);}
export function updateMap(snapshot,nextStatus){
 live=snapshot;status=nextStatus;
 if(live?.fix)lastPos=live.fix;
 syncPins();syncGoogle();
}
const STYLE_URLS={liberty:'https://tiles.openfreemap.org/styles/liberty',positron:'https://tiles.openfreemap.org/styles/positron'};
const ESRI_IMG='https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
const ESRI_REF_TR='https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}';
const ESRI_REF_PL='https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}';
function mapStyle(key){
 if(key==='sat') return {version:8,sources:{base:{type:'raster',tiles:[ESRI_IMG],tileSize:256,attribution:'© Esri, Maxar, Earthstar Geographics'}},layers:[{id:'base',type:'raster',source:'base',paint:{'raster-saturation':0.1,'raster-contrast':0.15}}]};
 if(key==='hybrid') return {version:8,sources:{base:{type:'raster',tiles:[ESRI_IMG],tileSize:256,attribution:'© Esri, Maxar, Earthstar Geographics'},refT:{type:'raster',tiles:[ESRI_REF_TR],tileSize:256},refP:{type:'raster',tiles:[ESRI_REF_PL],tileSize:256}},layers:[{id:'base',type:'raster',source:'base',paint:{'raster-saturation':0.15,'raster-contrast':0.15,'raster-brightness-min':0.05}},{id:'refT',type:'raster',source:'refT',paint:{'raster-opacity':0.9}},{id:'refP',type:'raster',source:'refP',paint:{'raster-opacity':0.9}}]};
 return STYLE_URLS[key]||STYLE_URLS.liberty;
}
let map=null,markers=[],gmap=null,gmarkers=[],mode='fallback',lastMapType='plan',lastCenter=null;
const G_KEY=new URLSearchParams(location.search).get('key')||localStorage.getItem('gmaps_key')||'';
if(G_KEY) document.getElementById('gkey').value=G_KEY;
function gType(s){return s==='satellite'?'satellite':s==='plan'?'roadmap':s==='terrain'?'terrain':'hybrid';}
function mlStyle(s){return s==='satellite'?mapStyle('sat'):s==='terrain'?mapStyle('positron'):s==='plan'?mapStyle('liberty'):mapStyle('hybrid');}
function setModeButtons(s){document.querySelectorAll('.styles button').forEach(x=>x.classList.toggle('on',x.dataset.s===s));}
let gAuthError='';window.gm_authFailure=()=>{gAuthError='key refused by Google (HTTP referrer restriction, API not enabled, or billing).';};
function loadGoogle(key){return new Promise((res,rej)=>{if(window.google&&window.google.maps)return res();gAuthError='';const sc=document.createElement('script');sc.src='https://maps.googleapis.com/maps/api/js?key='+encodeURIComponent(key)+'&callback=__ginit';sc.async=true;sc.onerror=()=>rej(new Error('script_error'));window.__ginit=()=>{setTimeout(()=>{if(gAuthError)rej(new Error(gAuthError));else res();},400);};document.head.appendChild(sc);setTimeout(()=>{if(gAuthError)rej(new Error(gAuthError));},6000);});}
function showGerr(msg){const e=document.getElementById('gerr');if(!e)return;e.hidden=false;e.textContent=msg;}
function pxPerMeter(lat,zoom){return 156543.03*Math.cos(lat*Math.PI/180)/Math.pow(2,zoom);}
export async function bootMap(){
 document.querySelectorAll('.styles button').forEach(b=>b.onclick=()=>{const s=b.dataset.s;setModeButtons(s);if(mode==='google'&&gmap)gmap.setMapTypeId(gType(s));else if(map){map.setStyle(mlStyle(s));map.once('styledata',syncPins);}lastMapType=s;});
 if(G_KEY){
  try{
   await loadGoogle(G_KEY);
   document.getElementById('keybox').style.display='flex';
   document.getElementById('clearkey').hidden=false;
   document.getElementById('gkey').value=G_KEY;
   document.getElementById('gkey').title='Google key active — paste a new key and press OK to replace it, ✕ to forget it';
   mode='google';
   document.getElementById('credit').style.display='none'; // Google draws its own attribution on the map
   gmap=new google.maps.Map(document.getElementById('map'),{center:{lat:HOME.lat,lng:HOME.lon},zoom:3,mapTypeId:gType(lastMapType||'plan'),mapTypeControl:false,zoomControl:true,gestureHandling:'greedy',streetViewControl:false,fullscreenControl:false,keyboardShortcuts:false});
   google.maps.event.addListenerOnce(gmap,'tilesloaded',mapReady);
   syncGoogle();return;
  }catch(e){
   showGerr('Google Maps n’a pas accepté la clé ou ne répond pas. '+String((e&&e.message)||e));
  }
 }
 try{
  if(!window.maplibregl)throw new Error('map_unavailable');
  if(window.maplibregl){
   map=new maplibregl.Map({container:'map',style:mlStyle(lastMapType||'hybrid'),center:[HOME.lon,HOME.lat],zoom:3,attributionControl:false});
   map.addControl(new maplibregl.NavigationControl({showCompass:true,visualizePitch:true}),'top-right');
   map.on('load',syncPins);map.on('zoomend',syncPins);
   map.once('idle',mapReady);
   syncPins();
  }
 }catch(e){document.getElementById('map').innerHTML='<div style="padding:40px;text-align:center;color:#475569">La carte est momentanément indisponible.</div>';mapReady();}
}
document.getElementById('savekey').onclick=()=>{const v=document.getElementById('gkey').value.trim();if(!v)return;localStorage.setItem('gmaps_key',v);const u=new URL(location.href);u.searchParams.set('key',v);location.href=u.toString();};
document.getElementById('clearkey').onclick=()=>{localStorage.removeItem('gmaps_key');const u=new URL(location.href);u.searchParams.delete('key');location.href=u.toString();};
if(G_KEY){document.getElementById('clearkey').hidden=false;}
function syncPins(){
 if(!map)return;markers.forEach(m=>m.remove());markers=[];
 if(!live||!live.fix||state()==='offline'){return;}
 const from=live.fix,acc=from.accM,st=state();
 // GPS accuracy halo, drawn at its real size in metres (clamped to stay readable).
 const ppm=pxPerMeter(from.lat,map.getZoom()),halo=Math.min(220,Math.max(34,2*acc/ppm)),frozen=st!=='live';
 const wrap=document.createElement('div');
 wrap.style.cssText='position:relative;width:'+halo+'px;height:'+halo+'px';
 wrap.style.filter=frozen?'grayscale(1)':'';
 wrap.innerHTML='<div style="position:absolute;inset:0;border-radius:50%;background:rgba(37,99,235,'+(frozen?'.08':'.18')+');border:2px solid rgba(37,99,235,'+(frozen?'.25':'.45')+')"></div><div style="position:absolute;left:50%;top:50%;width:30px;height:30px;margin:-15px;border-radius:50%;background:'+(frozen?'#64748b':'#2563eb')+';border:4px solid #fff;box-shadow:0 0 0 6px rgba(37,99,235,'+(frozen?'.1':'.3')+')"></div>';
 markers.push(new maplibregl.Marker({element:wrap}).setLngLat([from.lon,from.lat]).addTo(map));
 currentObjects().forEach(o=>{
  const pos=destPoint(o,from);
  const el=document.createElement('div');
  el.style.cssText='width:32px;height:32px;border-radius:50%;display:grid;place-items:center;font-size:17px;border:3px solid #fff;box-shadow:0 4px 14px rgba(0,0,0,.4);background:'+(o.frozen?'#64748b':o.distM<2?'#ef4444':o.distM<5?'#f59e0b':'#22c55e')+';'+(o.frozen?'opacity:.7;':'');
  el.textContent=o.ico;el.title=o.label+' '+o.distM.toFixed(1)+'m';
  markers.push(new maplibregl.Marker({element:el}).setLngLat([pos.lon,pos.lat]).addTo(map));
 });
 const moved=!lastCenter||Math.hypot(from.lat-lastCenter.lat,from.lon-lastCenter.lon)*111320>8;
 if(moved){
  const first=!lastCenter;lastCenter={lat:from.lat,lon:from.lon};
  try{
   if(first) map.jumpTo({center:[from.lon,from.lat],zoom:Math.max(map.getZoom(),DETAIL_ZOOM)});
   else map.easeTo({center:[from.lon,from.lat],duration:600});
  }catch(e){}
 }
}
function syncGoogle(){
 if(!gmap)return;gmarkers.forEach(m=>m.setMap(null));gmarkers=[];
 if(!live||!live.fix||state()==='offline')return;
 const from=live.fix,acc=from.accM,st=state(),frozen=st!=='live';
 currentObjects().forEach(o=>{
  const pos=destPoint(o,from);
  gmarkers.push(new google.maps.Marker({position:{lat:pos.lat,lng:pos.lon},map:gmap,title:o.label+' '+o.distM.toFixed(1)+'m',label:{text:o.ico,color:'#fff',fontSize:'15px'},opacity:frozen?0.7:1}));
 });
 gmarkers.push(new google.maps.Circle({map:gmap,center:{lat:from.lat,lng:from.lon},radius:Math.max(5,acc),strokeColor:'#2563eb',strokeOpacity:frozen?.25:.45,strokeWeight:2,fillColor:'#2563eb',fillOpacity:frozen?.08:.18}));
 gmarkers.push(new google.maps.Marker({position:{lat:from.lat,lng:from.lon},map:gmap,title:'Proche suivi',zIndex:9,icon:{path:google.maps.SymbolPath.CIRCLE,scale:9,fillColor:frozen?'#64748b':'#2563eb',fillOpacity:1,strokeColor:'#fff',strokeWeight:4}}));
 const moved=!lastCenter||Math.hypot(from.lat-lastCenter.lat,from.lon-lastCenter.lon)*111320>8;
 if(moved){
  const first=!lastCenter;lastCenter={lat:from.lat,lon:from.lon};
  gmap.panTo({lat:from.lat,lng:from.lon});
  if(first&&gmap.getZoom()<DETAIL_ZOOM) gmap.setZoom(DETAIL_ZOOM);
 }
}
document.getElementById('recenter').onclick=()=>{
 const f=(live&&live.fix)||(lastPos?{lat:lastPos.lat,lon:lastPos.lon}:HOME);
 lastCenter={lat:f.lat,lon:f.lon};
 if(mode==='google'&&gmap)gmap.panTo({lat:f.lat,lng:f.lon});
 else if(map)map.easeTo({center:[f.lon,f.lat],zoom:Math.max(map.getZoom(),DETAIL_ZOOM),duration:600});
};

new ResizeObserver(()=>{
 if(map)map.resize();
 if(gmap)google.maps.event.trigger(gmap,'resize');
}).observe(document.getElementById('map'));
