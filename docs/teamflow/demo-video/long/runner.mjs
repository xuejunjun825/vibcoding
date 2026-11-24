import fs from 'node:fs';
export const FR='/tmp/rec/long/frames/';
export function makeBeat(d){
  const log=JSON.parse(fs.existsSync('/tmp/rec/long/beats.json')?fs.readFileSync('/tmp/rec/long/beats.json','utf8'):'[]');
  let seq=log.length;
  const mark=async(box)=>{ if(!box) return; await d.ev(`(()=>{const old=document.getElementById('__hl');if(old)old.remove();const el=document.createElement('div');el.id='__hl';el.style.cssText='position:fixed;left:${box.x-6}px;top:${box.y-6}px;width:${box.w+12}px;height:${box.h+12}px;border:3px solid #2B6FE5;border-radius:10px;box-shadow:0 0 0 6px rgba(43,111,229,.18);pointer-events:none;z-index:99999';document.body.appendChild(el);})()`); };
  const unmark=async()=>{ await d.ev(`(()=>{const e=document.getElementById('__hl');if(e)e.remove();})()`); };
  const shotAs=async(n,tag)=>{ const f=n+'-'+tag+'.png'; await d.shot(f.replace(/\.png$/,'')); fs.copyFileSync('/tmp/rec/frames/'+f, FR+f); return f; };
  const beat=async({role,title,sub,pre,act})=>{ seq++; const n=String(seq).padStart(2,'0'); let status='ok'; const files=[];
    try{ const box=pre? await pre():null; if(box){ await mark(box); await d.sleep(350);} files.push(await shotAs(n,'a')); if(box) await unmark();
         if(act) await act(); await d.sleep(400); files.push(await shotAs(n,'b')); }
    catch(e){ status='err:'+e.message.slice(0,90); try{ files.push(await shotAs(n,'b')); }catch(_){} }
    log.push({i:seq,role,title,sub,file:files[0],after:files[1],status}); fs.writeFileSync('/tmp/rec/long/beats.json',JSON.stringify(log,null,2));
    console.log(n, role, '|', title, '=>', status); };
  return {beat};
}
