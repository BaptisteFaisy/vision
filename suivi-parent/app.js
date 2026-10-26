// Suivi parent — démo simulée + point d'entrée live (à brancher).
// Contrat live attendu (JSON téléphone -> site), 2 Hz :
// { t, lat, lon, capDeg, speedKmh, objets:[{id,label,distM,angleDeg}] }
// angleDeg : 0 = devant, - = gauche, + = droite (issu box + FOV, voir README).
const DEMO_OBSTACLES = [
  { id:'poteau', label:'poteau devant', ico:'🚧', distM:2.3, angleDeg:-12, color:'orange' },
  { id:'personne', label:'personne à droite', ico:'🚶', distM:1.4, angleDeg:18, color:'red' },
  { id:'poubelle', label:'poubelle à gauche', ico:'🗑️', distM:3.1, angleDeg:-28, color:'orange' },
  { id:'voiture', label:'voiture', ico:'🚗', distM:4.8, angleDeg:8, color:'green' },
];
let passed = new Set(JSON.parse(localStorage.getItem('passed')||'[]'));
let simT = 0, playing = true;

const list = document.getElementById('ob-list');
function renderList(){
  list.innerHTML='';
  const sorted=[...DEMO_OBSTACLES].sort((a,b)=>a.distM-b.distM);
  document.getElementById('count').textContent=sorted.length;
  sorted.forEach(o=>{
    const isP=passed.has(o.id);
    const li=document.createElement('li');
    if(isP) li.className='passed';
    li.innerHTML=`<div class="ico" style="background:${o.color==='red'?'#ef4444':o.color==='orange'?'#f59e0b':'#22c55e'}">${isP?'✓':o.ico}</div>
      <div class="meta"><b>${o.label}</b><span>${isP?'dépassé — bien évité':o.angleDeg<0?'côté gauche':o.angleDeg>0?'côté droit':'plein devant'} • ${o.distM.toFixed(1)} m</span></div>
      <span class="pill ${isP?'green':o.distM<2?'red':o.distM<5?'orange':'green'}">${isP?'dépassé ✓':o.distM.toFixed(1)+' m'}</span>`;
    li.onclick=()=>{ togglePassed(o.id); };
    list.appendChild(li);
  });
}
function togglePassed(id){
  const li=[...list.children].find(()=>true);
  if(passed.has(id)) passed.delete(id);
  else{
    passed.add(id);
    const el=[...list.children].find(e=>e.textContent.includes(DEMO_OBSTACLES.find(o=>o.id===id).label));
    if(el){el.classList.add('just-passed');setTimeout(()=>el.classList.remove('just-passed'),600);}
  }
  localStorage.setItem('passed',JSON.stringify([...passed]));
  renderList(); drawRadar(); syncMapPins();
}

// ---- Radar top-down ----
const cv=document.getElementById('radar'), ctx=cv.getContext('2d');
function drawRadar(){
  const W=cv.width,H=cv.height,cx=W/2,cy=H/2+40,scale=42; // 42px = 1m
  ctx.clearRect(0,0,W,H);
  // cercles 2/5m + Nord
  [[2,'2 m'],[5,'5 m']].forEach(([r,txt])=>{ctx.beginPath();ctx.arc(cx,cy,r*scale,0,7);ctx.strokeStyle='#334155';ctx.setLineDash([6,8]);ctx.stroke();ctx.setLineDash([]);ctx.fillStyle='#64748b';ctx.font='12px sans-serif';ctx.fillText(txt,cx+r*scale-8,cy-6);});
  ctx.fillStyle='#64748b';ctx.font='bold 13px sans-serif';ctx.fillText('▲ NORD',cx-28,24);
  // cône vision
  ctx.beginPath();ctx.moveTo(cx,cy);ctx.lineTo(cx-90,cy-170);ctx.lineTo(cx+90,cy-170);ctx.closePath();ctx.fillStyle='rgba(59,130,246,.15)';ctx.fill();
  // user
  ctx.beginPath();ctx.arc(cx,cy,20,0,7);ctx.fillStyle='#2563eb';ctx.fill();ctx.lineWidth=4;ctx.strokeStyle='#fff';ctx.stroke();
  ctx.fillStyle='#fff';ctx.font='bold 13px sans-serif';ctx.textAlign='center';ctx.fillText('Léo',cx,cy+5);
  // obstacles : angle 0 = devant (haut), x = d*sin, y = -d*cos
  DEMO_OBSTACLES.forEach(o=>{
    const isP=passed.has(o.id);
    const rad=o.angleDeg*Math.PI/180;
    const x=cx+o.distM*Math.sin(rad)*scale, y=cy-o.distM*Math.cos(rad)*scale;
    const col=isP?'#22c55e':o.distM<2?'#ef4444':o.distM<5?'#f59e0b':'#22c55e';
    ctx.beginPath();ctx.arc(x,y,isP?13:17+Math.max(0,3-o.distM),0,7);
    ctx.fillStyle=col;ctx.globalAlpha=isP?.55:1;ctx.fill();ctx.globalAlpha=1;ctx.lineWidth=3;ctx.strokeStyle='#fff';ctx.stroke();
    ctx.fillStyle='#fff';ctx.font='bold 13px sans-serif';ctx.fillText(isP?'✓':o.ico,x,y+5);
    ctx.fillStyle=isP?'#22c55e':'#0f172a';const label=`${o.label.split(' ')[0]} ${o.distM.toFixed(1)}m${isP?' ✓':''}`;
    const w=ctx.measureText(label).width+16;ctx.beginPath();ctx.roundRect(x-w/2,y-34,w,20,10);ctx.fill();ctx.fillStyle=isP?'#06130b':'#fff';ctx.font='bold 11px sans-serif';ctx.fillText(label,x,y-20);
  });
  ctx.textAlign='left';
}

// ---- Carte Leaflet ----
const map=L.map('map',{scrollWheelZoom:false}).setView([48.8566,2.3522],18);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
const userMarker=L.marker([48.8566,2.3522],{icon:L.divIcon({className:'',html:'<div class="ob-pin" style="background:#2563eb">▲</div>',iconSize:[34,34],iconAnchor:[17,17]})}).addTo(map).bindTooltip('Léo • 4,2 km/h',{permanent:true,direction:'top'});
let trail=L.polyline([[48.8569,2.3522],[48.85675,2.3522],[48.8566,2.3522]],{color:'#2563eb',weight:5,dashArray:'1 10',lineCap:'round'}).addTo(map);
let pins=[];
function metersToDeg(d,ang){const r=ang*Math.PI/180;return [ -d*Math.cos(r)/111320, d*Math.sin(r)/(111320*Math.cos(48.8566*Math.PI/180)) ];}
function syncMapPins(){
  pins.forEach(p=>map.removeLayer(p.m));pins=[];
  DEMO_OBSTACLES.forEach(o=>{
    const isP=passed.has(o.id);
    const [dLat,dLon]=metersToDeg(o.distM,o.angleDeg);
    const m=L.marker([48.8566+dLat,2.3522+dLon],{icon:L.divIcon({className:'',html:`<div class="ob-pin ${isP?'green':o.color}">${isP?'✓':o.ico}</div><div class="ob-label ${isP?'passed':''}">${o.label.split(' ')[0]} ${o.distM.toFixed(1)}m${isP?' ✓':''}</div>`,iconSize:[34,34],iconAnchor:[17,17]})}).addTo(map);
    pins.push({m,id:o.id});
  });
}
document.getElementById('toggle-trail').onchange=e=>{e.target.checked?trail.addTo(map):map.removeLayer(trail);};
document.getElementById('btn-recenter').onclick=()=>map.setView([48.8566,2.3522],18);
document.getElementById('btn-demo').onclick=()=>{playing=!playing;document.getElementById('btn-demo').textContent=playing?'⏸ Pause démo':'▶ Démo';};
document.getElementById('btn-live').onclick=()=>alert('Mode live à brancher : le téléphone POST {lat,lon,capDeg,objets[]} vers /api/share?code=A3F9K2 (voir README). En attendant, la démo simule ce flux.');
document.getElementById('btn-voice').onclick=()=>alert('Démo : message vocal parent → téléphone (à brancher via push).');
// petite simu : la personne la plus proche se fait dépasser en boucle pour montrer l'animation
setInterval(()=>{
  if(!playing) return; simT++;
  const p=DEMO_OBSTACLES.find(o=>o.id==='personne');
  p.distM=Math.max(.6,1.4+Math.sin(simT/4)*1.6);
  p.angleDeg=18+Math.sin(simT/6)*30; // passe de droite vers derrière
  if(p.distM<1&&p.angleDeg>35&&!passed.has('personne')) togglePassed('personne');
  if(simT%20===0&&passed.has('personne')){passed.delete('personne');localStorage.setItem('passed',JSON.stringify([...passed]));renderList();}
  else{renderList();drawRadar();syncMapPins();}
  const s=Math.floor(simT%60);document.getElementById('clock').textContent=`il y a ${1+s%3} s`;
},900);

renderList();drawRadar();syncMapPins();
