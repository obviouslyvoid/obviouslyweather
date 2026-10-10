// SPDX-License-Identifier: GPL-3.0-or-later
const fs=require('fs');
const sharp=require('sharp');
let svg=fs.readFileSync('design/meteocons/package/fill/overcast-day-rain.svg','utf8');
const palette={'#FBBF24':'#F4F4F4','#F8AF18':'#BEBEBE','#B0BCCD':'#9A9A9A','#94A3B8':'#777777','#F3F7FE':'#FFFFFF','#E6EFFC':'#C4C4C4','#0A5AD4':'#BBBBBB'};
for(const [from,to] of Object.entries(palette)) svg=svg.replaceAll(from,to);
fs.writeFileSync('design/meteocons/launcher-gray.svg',svg);
const attrs=s=>Object.fromEntries([...s.matchAll(/([\w-]+)="([^"]+)"/g)].map(m=>[m[1],m[2]]));
const gradients={};for(const m of svg.matchAll(/<linearGradient\b([^>]+)>([\s\S]*?)<\/linearGradient>/g)){const a=attrs(m[1]);gradients[a.id]={...a,stops:[...m[2].matchAll(/<stop\b([^>]+)\/>/g)].map(m=>attrs(m[1]))};}
let paths='';
for(const m of svg.matchAll(/<(path|circle)\b([^>]+)\/>/g)) {
 const a=attrs(m[2]);let d=a.d;
 if(m[1]==='circle'){const cx=+a.cx,cy=+a.cy,r=+a.r;d=`M${cx-r},${cy}a${r},${r} 0,1 0,${2*r},0a${r},${r} 0,1 0,-${2*r},0`;}
 const fill=a.fill??'none',g=gradients[fill.match(/url\(#([^\)]+)\)/)?.[1]];
 let extra='';if(a.stroke)extra+=` android:strokeColor="${a.stroke}" android:strokeWidth="${a['stroke-width']??1}" android:strokeLineCap="${a['stroke-linecap']??'butt'}"`;
 let node=`<path android:pathData="${d}" android:fillColor="${g||fill==='none'?'#00000000':fill}"${extra}`;
 if(g){node=node.replace(' android:fillColor="#00000000"','');node+=`><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="${g.x1}" android:startY="${g.y1}" android:endX="${g.x2}" android:endY="${g.y2}">${g.stops.map(s=>`<item android:color="${s['stop-color']}" android:offset="${s.offset??0}"/>`).join('')}</gradient></aapt:attr></path>`;}else node+='/>';
 const tr=a.transform?.match(/translate\(([\d.-]+),\s*([\d.-]+)\)/);if(tr) node=`<group android:translateX="${tr[1]}" android:translateY="${tr[2]}">${node}</group>`;
 paths+=node+'\n';
}
const header=`<?xml version="1.0" encoding="utf-8"?>\n<!-- Meteocons by Bas Milius, MIT. Grayscale adaptation; see assets/licenses/meteocons-MIT.txt. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n`;
fs.writeFileSync('app/src/main/res/drawable/ic_launcher_foreground.xml',header+`<group android:translateX="14.94" android:translateY="12.15" android:scaleX="0.62" android:scaleY="0.62">\n${paths}</group></vector>`);
const appHeader=header.replaceAll('"108dp"','"72dp"').replaceAll('"108"','"72"');
const tile='<path android:fillColor="#000000" android:pathData="M17,0H55A17,17 0,0 1,72,17V55A17,17 0,0 1,55,72H17A17,17 0,0 1,0,55V17A17,17 0,0 1,17,0Z" />';
fs.writeFileSync('app/src/main/res/drawable/app_mark.xml',appHeader+tile+`<group android:translateX="-3.06" android:translateY="-5.85" android:scaleX="0.62" android:scaleY="0.62">\n${paths}</group></vector>`);
const lightPalette={'#F4F4F4':'#909090','#BEBEBE':'#A4A4A4','#9A9A9A':'#BDBDBD','#777777':'#969696','#FFFFFF':'#555555','#C4C4C4':'#777777','#BBBBBB':'#787878'};
let lightPaths=paths;
for(const [from,to] of Object.entries(lightPalette)) lightPaths=lightPaths.replaceAll(from,to);
fs.writeFileSync('app/src/main/res/drawable/app_mark_light.xml',appHeader+`<group android:translateX="-3.06" android:translateY="-5.85" android:scaleX="0.62" android:scaleY="0.62">\n${lightPaths}</group></vector>`);
// The Android themed-icon mask uses the same silhouettes with flat opaque fills.
const mono=paths.replace(/<aapt:attr[\s\S]*?<\/aapt:attr>/g,'').replace(/android:fillColor="#[A-Fa-f0-9]{6}"/g,'android:fillColor="#FFFFFF"').replace(/android:fillColor="#00000000"(?=[^>]*>\s*<\/path>)/g,'android:fillColor="#FFFFFF"').replace(/android:strokeColor="#[A-Fa-f0-9]{6}"/g,'android:strokeColor="#FFFFFF"');
fs.writeFileSync('app/src/main/res/drawable/ic_launcher_monochrome.xml',header+`<group android:translateX="14.94" android:translateY="12.15" android:scaleX="0.62" android:scaleY="0.62">\n${mono}</group></vector>`);
fs.copyFileSync('design/meteocons/package/LICENSE','app/src/main/assets/licenses/meteocons-MIT.txt');
(async()=>{
 fs.mkdirSync('dist',{recursive:true});
 const art=`<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 108 108"><g transform="translate(14.94,12.15) scale(.62)">${svg.replace(/<svg[^>]*>|<\/svg>/g,'')}</g></svg>`;
 const fg=await sharp(Buffer.from(art)).png().toBuffer();
 const icon=round=>`<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="18 18 72 72"><defs><clipPath id="mask"><rect x="18" y="18" width="72" height="72" rx="${round?36:17}"/></clipPath></defs><g clip-path="url(#mask)"><rect x="18" y="18" width="72" height="72" fill="black"/><g transform="translate(14.94,12.15) scale(.62)">${svg.replace(/<svg[^>]*>|<\/svg>/g,'')}</g></g></svg>`;
 const square=await sharp(Buffer.from(icon(false))).png().toBuffer(); const circle=await sharp(Buffer.from(icon(true))).png().toBuffer();
 await sharp(square).resize(192,192).png().toFile('dist/launcher.png');
 await sharp({create:{width:936,height:480,channels:4,background:'#eee'}}).composite([{input:square,left:24,top:24},{input:circle,left:480,top:24}]).png().toFile('dist/launcher-preview.png');
 for(const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
  await sharp(square).resize(size,size).webp({lossless:true}).toFile(`app/src/main/res/mipmap-${density}/ic_launcher.webp`);
  await sharp(circle).resize(size,size).webp({lossless:true}).toFile(`app/src/main/res/mipmap-${density}/ic_launcher_round.webp`);
 }
 const {data,info}=await sharp(fg).ensureAlpha().raw().toBuffer({resolveWithObject:true});let b=[1080,1080,0,0];
 for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++)if(data[(y*info.width+x)*4+3]>64)b=[Math.min(b[0],x),Math.min(b[1],y),Math.max(b[2],x),Math.max(b[3],y)];
 console.log('Visible bounds:',b,'Centre:',(b[0]+b[2])/20,(b[1]+b[3])/20);
})();
