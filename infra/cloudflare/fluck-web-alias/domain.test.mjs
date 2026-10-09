import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

test('fluck.ai proxies to the isolated portal without weakening Origin or cookie boundaries', async()=>{
  const original=globalThis.fetch;
  let seen;
  globalThis.fetch=async(url,options)=>{
    seen={url,options};
    return new Response(null,{status:302,headers:[['Location','https://fluck.ai/auth'],['Set-Cookie','session=fixture; Path=/; Secure; HttpOnly'],['Set-Cookie','legacy=fixture; Domain=fluck.risaboss.com; Secure']]});
  };
  try{
    const response=await worker.fetch(new Request('https://fluck.ai/api/open?x=1',{method:'POST',headers:{Origin:'https://fluck.ai','Content-Type':'application/json'},body:'{}'}),{FLUCK_WEB_ALIAS_SECRET:'test-secret'});
    assert.equal(seen.url,'https://api.risaboss.com/functions/v1/fluck-ai/api/open?x=1');
    assert.equal(seen.options.headers.get('Origin'),'https://fluck.ai');
    assert.equal(seen.options.headers.get('X-Fluck-Web-Alias'),'fluck.ai');
    assert.equal(seen.options.headers.get('X-Fluck-Web-Alias-Secret'),'test-secret');
    assert.deepEqual(response.headers.getSetCookie(),['session=fixture; Path=/; Secure; HttpOnly']);
  }finally{globalThis.fetch=original;}
});

test('canonical redirects retain paths and queries and refuse unrelated hosts', async()=>{
  for(const url of ['http://fluck.ai/auth?code=fixture','http://www.fluck.ai/auth?code=fixture','https://www.fluck.ai/auth?code=fixture']){
    const response=await worker.fetch(new Request(url),{});
    assert.equal(response.status,308);
    assert.equal(response.headers.get('location'),'https://fluck.ai/auth?code=fixture');
  }
  assert.equal((await worker.fetch(new Request('https://attacker.example/'),{})).status,421);
});

test('both portal domains keep the native Apple association response', async()=>{
  for(const host of ['fluck.ai','fluck.risaboss.com']){
    const response=await worker.fetch(new Request(`https://${host}/.well-known/apple-app-site-association`),{});
    assert.equal(response.status,200);
    assert.equal((await response.json()).applinks.details[0].appIDs[0],'7X4CJM22GN.app.fluck.ios');
  }
});
