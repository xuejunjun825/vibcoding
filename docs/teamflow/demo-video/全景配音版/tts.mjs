import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const plan=JSON.parse(fs.readFileSync('/tmp/nv/plan.json','utf8'));
let total=0; const durs=[];
plan.forEach((p,i)=>{
  const a=`/tmp/nv/audio/${String(i+1).padStart(2,'0')}.aiff`;
  execFileSync('say',['-v','Tingting','-r','175','-o',a,p.say]);
  const d=parseFloat(execFileSync('ffprobe',['-v','error','-show_entries','format=duration','-of','csv=p=0',a]).toString().trim());
  durs.push(d); total+=d+0.35;
  console.log(i+1, d.toFixed(2)+'s', p.say.slice(0,28));
});
fs.writeFileSync('/tmp/nv/durs.json', JSON.stringify(durs));
console.log('TOTAL', total.toFixed(1)+'s');
