// SPDX-License-Identifier: GPL-3.0-or-later
const fs=require('fs');
const sharp=require('sharp');
(async()=>{
 fs.mkdirSync('dist',{recursive:true});
const items=[];
for(const [i,name] of ['partly-cloudy-day-rain','overcast-day-rain'].entries()) {
 const png=await sharp(Buffer.from(fs.readFileSync(`design/meteocons/package/fill/${name}.svg`))).resize(1024,1024).png().toBuffer();
 const {data,info}=await sharp(png).recomb([[.2126,.7152,.0722],[.2126,.7152,.0722],[.2126,.7152,.0722]]).ensureAlpha().raw().toBuffer({resolveWithObject:true});
 let b=[1024,1024,0,0];for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++)if(data[(y*info.width+x)*info.channels+info.channels-1]>8)b=[Math.min(b[0],x),Math.min(b[1],y),Math.max(b[2],x),Math.max(b[3],y)];
 console.log(name,info.channels,b);
 const art=await sharp(data,{raw:info}).extract({left:b[0],top:b[1],width:b[2]-b[0]+1,height:b[3]-b[1]+1}).resize(310,310,{fit:'inside'}).png().toBuffer();
 const meta=await sharp(art).metadata();
 const icon=await sharp({create:{width:432,height:432,channels:4,background:'#080808'}}).composite([{input:art,left:Math.round((432-meta.width)/2),top:Math.round((432-meta.height)/2)}]).png().toBuffer();
 items.push({input:icon,left:24+i*456,top:24});
}
await sharp({create:{width:936,height:480,channels:4,background:'#eee'}}).composite(items).png().toFile('dist/launcher-candidates.png');
})();
