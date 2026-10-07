// Prepare an unlinked, isolated local stack. Does not start Docker or deploy anything.
import {execFileSync} from 'node:child_process';
import {mkdir,readFile,writeFile,cp,access} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {resolve,relative,join} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url));
const target=resolve(process.argv[2]??'/tmp/boss-app-sharing-e2e');
const temporary=[tmpdir(),'/tmp','/private/tmp'].some(base=>{const rel=relative(resolve(base),target);return rel&&!rel.startsWith('..')});
if(!temporary||!target.endsWith('boss-app-sharing-e2e'))throw Error('Use a fresh temporary boss-app-sharing-e2e directory');
try{await access(join(target,'supabase/config.toml'));throw Error('Local stack already exists; preserve it or choose a fresh temp parent');}catch(error){if(error.code!=='ENOENT')throw error;}
await mkdir(target,{recursive:true,mode:0o700});
execFileSync('supabase',['init','--workdir',target],{stdio:'pipe'});
const config=join(target,'supabase/config.toml');let text=await readFile(config,'utf8');
for(const [from,to] of [[54321,55431],[54322,55432],[54320,55430],[54323,55433],[54324,55434],[54329,55439],[54327,55437],[8083,55438]])text=text.replaceAll(String(from),String(to));
for(const section of ['studio','realtime','storage','storage.s3_protocol','storage.vector','analytics','db.seed']){
 const escaped=section.replaceAll('.','\\.');text=text.replace(new RegExp('(\\['+escaped+'\\]\\n(?:#[^\\n]*\\n)*)enabled = true'),'$1enabled = false');
}
text+='\n[functions.app-sharing]\nverify_jwt = false\n[functions.app-sharing-maintenance]\nverify_jwt = false\n';
await writeFile(config,text);
await mkdir(join(target,'supabase/migrations'),{recursive:true});
await cp(join(root,'supabase/migrations/20260928000000_application_sharing.sql'),join(target,'supabase/migrations/20260928000000_application_sharing.sql'));
for(const name of ['app-sharing','app-sharing-maintenance','_shared'])await cp(join(root,'supabase/functions',name),join(target,'supabase/functions',name),{recursive:true});
await writeFile(join(target,'functions.env'),'APP_SHARING_MAINTENANCE_KEY='+randomBytes(32).toString('base64url')+'\n',{mode:0o600});
await mkdir(join(target,'docker'),{mode:0o700});await writeFile(join(target,'docker/config.json'),'{}\n',{mode:0o600});
console.log('Prepared isolated app-only stack; no services started, no remote changes');
