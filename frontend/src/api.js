let csrf
export function clearCsrf(){csrf=null}
export async function api(path,options={},retried=false){
 const method=options.method||'GET';const headers={...(options.headers||{})}
 if(method!=='GET'&&method!=='HEAD'){if(!csrf){const r=await fetch('/api/auth/csrf');if(!r.ok)throw new Error('无法获取安全令牌');csrf=await r.json()}headers[csrf.headerName]=csrf.token}
 if(options.body&&!(options.body instanceof FormData))headers['Content-Type']='application/json'
 const response=await fetch(path,{...options,headers,body:options.body instanceof FormData?options.body:options.body?JSON.stringify(options.body):undefined})
 if(!response.ok){
  let data={};let message=`请求失败（${response.status}）`
  try{data=await response.json();message=data.message||message}catch{}
  if(response.status===403&&!retried&&method!=='GET'&&method!=='HEAD'&&(!data.code||data.code==='FORBIDDEN')){clearCsrf();return api(path,options,true)}
  const error=new Error(message);Object.assign(error,data,{status:response.status});throw error
 }
 if(response.status===204)return null;const type=response.headers.get('content-type')||'';return type.includes('json')?response.json():response
}
