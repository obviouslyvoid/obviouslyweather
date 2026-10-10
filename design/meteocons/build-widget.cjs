// SPDX-License-Identifier: GPL-3.0-or-later
// Reproducible XML assets, adapted from the bundled MIT Meteocons collection.
const fs = require('fs');
const sharp = require('sharp');
const names = ['clear-day','clear-night','partly-cloudy-day','partly-cloudy-night','overcast','overcast-rain','overcast-snow','thunderstorms','mist','not-available'];
const attrs = s => Object.fromEntries([...s.matchAll(/([\w-]+)="([^"]+)"/g)].map(m=>[m[1],m[2]]));
const gray = c => {
 if (!/^#[\da-f]{6}$/i.test(c)) return c;
 const n=parseInt(c.slice(1),16), v=Math.round(.2126*(n>>16)+.7152*((n>>8)&255)+.0722*(n&255));
 return '#'+v.toString(16).padStart(2,'0').repeat(3);
};
const inverse = c => {
 const rgb=c.slice(-6),alpha=c.length===9?c.slice(1,3):'';
 if(alpha==='00') return c;
 const value=0xFFFFFF^parseInt(rgb,16);
 return '#'+alpha+value.toString(16).padStart(6,'0');
};
async function main() {
 fs.mkdirSync('app/src/main/res/layout',{recursive:true});
 for(const name of names) {
  let svg=fs.readFileSync(`design/meteocons/package/fill/${name}.svg`,'utf8').replace(/#[\da-f]{6}/gi,gray);
  // The sun and moon belong to the same white family as the front cloud. Preserve
  // rear-cloud/mist shading, and keep thin sun rays softer than the filled body.
  if(name==='clear-night'||name==='partly-cloudy-night')
   svg=svg.replaceAll('#b8b8b8','#f7f7f7').replaceAll('#acacac','#eeeeee');
  if(name==='clear-day'||name==='partly-cloudy-day')
   svg=svg.replaceAll('#c1c1c1','#f7f7f7')
    .replaceAll('stop-color="#b4b4b4"','stop-color="#eeeeee"')
    .replaceAll('stroke="#b4b4b4"','stroke="#eeeeee"')
    .replaceAll('fill="#b4b4b4"','fill="#dddddd"');
  if(name==='not-available') svg=svg.replaceAll('#282828','#f7f7f7');
  const gradients={};
  for(const m of svg.matchAll(/<linearGradient\b([^>]+)>([\s\S]*?)<\/linearGradient>/g)) {const a=attrs(m[1]);gradients[a.id]={...a,stops:[...m[2].matchAll(/<stop\b([^>]+)\/>/g)].map(m=>attrs(m[1]))};}
  // These static source files have only untransformed grouping, paths and circles.
  if (/<g[^>]*transform=/.test(svg)) throw new Error('Unexpected group transform: '+name);
  let paths='';
  for(const m of svg.matchAll(/<(path|circle)\b([^>]+)\/>/g)) {
   const a=attrs(m[2]); let d=a.d;
   if(m[1]==='circle') {const x=+a.cx,y=+a.cy,r=+a.r;d=`M${x-r},${y}a${r},${r} 0,1 0,${r*2},0a${r},${r} 0,1 0,-${r*2},0`;}
   const fill=a.fill??'none',g=gradients[fill.match(/url\(#([^\)]+)\)/)?.[1]];
   let extra=a.stroke?` android:strokeColor="${a.stroke}" android:strokeWidth="${a['stroke-width']??1}" android:strokeLineCap="${a['stroke-linecap']??'butt'}"`:'';
   if(a.opacity)extra+=` android:fillAlpha="${a.opacity}" android:strokeAlpha="${a.opacity}"`;
   let node=`<path android:pathData="${d}"${extra}`;
   if(g) node+=`><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="${g.x1}" android:startY="${g.y1}" android:endX="${g.x2}" android:endY="${g.y2}">${g.stops.map(s=>`<item android:color="${s['stop-color']}" android:offset="${s.offset??0}"/>`).join('')}</gradient></aapt:attr></path>`;
   else node+=` android:fillColor="${fill==='none'?'#00000000':fill}"/>`;
   const tr=a.transform?.match(/translate\(([\d.-]+),\s*([\d.-]+)\)/);
   if(a.transform && !tr)throw new Error('Unexpected path transform');
   if(tr)node=`<group android:translateX="${tr[1]}" android:translateY="${tr[2]}">${node}</group>`;
   paths+=node+'\n';
  }
  // Center visible artwork, rather than the differently padded source SVG canvases.
  const {data,info}=await sharp(Buffer.from(svg)).resize(512,512).ensureAlpha().raw().toBuffer({resolveWithObject:true});
  let left=512,top=512,right=0,bottom=0;
  for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++)if(data[(y*info.width+x)*4+3]>=8) {
   left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);
  }
  const cx=(left+right)/8,cy=(top+bottom)/8,scale=84/(Math.max(right-left+1,bottom-top+1)/4);
  const tx=48-cx*scale,ty=48-cy*scale;
  if(Math.abs(cx*scale+tx-48)>0.001||Math.abs(cy*scale+ty-48)>0.001)throw new Error('Uncentered icon: '+name);
  const asset=`<?xml version="1.0" encoding="utf-8"?>\n<!-- Meteocons by Bas Milius, MIT; grayscale adaptation, centered using visible alpha bounds. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="64dp" android:height="64dp" android:viewportWidth="96" android:viewportHeight="96"><group android:translateX="${tx.toFixed(4)}" android:translateY="${ty.toFixed(4)}" android:scaleX="${scale.toFixed(5)}" android:scaleY="${scale.toFixed(5)}">${paths}</group></vector>`;
  const path=`app/src/main/res/drawable/widget_${name.replaceAll('-','_')}`;
  fs.writeFileSync(path+'.xml',asset);
  // Paired dark artwork preserves every path, alpha, gradient stop and viewport.
  // Mirroring luminance makes the same details readable on a light background.
  fs.writeFileSync(path+'_dark.xml',asset.replace(/(android:(?:fillColor|strokeColor|color)=")(#(?:[\da-f]{8}|[\da-f]{6}))(")/gi,
   (_,prefix,color,suffix)=>prefix+inverse(color)+suffix));
 }
 console.log('10 paired light/dark weather vectors generated.');
}
main().catch(e=>{console.error(e);process.exit(1);});
