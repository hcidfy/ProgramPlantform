import { marked } from 'marked'

marked.setOptions({ gfm: true, breaks: true })

/**
 * 题面 Markdown → HTML。
 * 题面里的图片写 ![](/api/files/{id})，需要带 Authorization 才能取回，
 * 所以渲染完再调 hydrateImages 把 <img> 换成 blob URL。
 */
export function renderMarkdown(markdown: string): string {
  return marked.parse(markdown, { async: false }) as string
}

/** 把需要鉴权的图片换成 blob URL（浏览器 <img> 不会自己带 Authorization 头）。 */
export async function hydrateAuthImages(container: HTMLElement, token: string | null): Promise<void> {
  const images = Array.from(container.querySelectorAll('img'))
  await Promise.all(images.map(async (img) => {
    const src = img.getAttribute('src') ?? ''
    if (!src.startsWith('/api/files/')) {
      return
    }
    try {
      const res = await fetch(src, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      if (!res.ok) {
        img.classList.add('img-broken')
        return
      }
      const blob = await res.blob()
      img.src = URL.createObjectURL(blob)
    } catch {
      img.classList.add('img-broken')
    }
  }))
}
