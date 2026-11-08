export async function getHealth(fetcher = fetch) {
 const response = await fetcher('/api/health')
 if (!response.ok) throw new Error('健康检查请求失败')
 const data = await response.json()
 if (data.status !== 'UP') throw new Error('后端状态异常')
 return data.status
}
