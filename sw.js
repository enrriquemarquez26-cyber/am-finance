// La caché contiene únicamente la interfaz y recursos públicos locales.
const raiz = new URL(self.registration.scope);
const prefijo = "balance-one:" + raiz.pathname + ":";
const nombre = prefijo + "0.7.37";
const inicio = new URL("index.html", raiz).href;
const manifest = new URL("manifest.webmanifest", raiz).href;

self.addEventListener("install", evento => {
  evento.waitUntil((async () => {
    const cache = await caches.open(nombre);
    const html = await fetch(inicio, {cache: "reload"});
    if (!html.ok || !(await html.clone().text()).includes("Balance One 0.7.37 Beta")) {
      throw new Error("Versión de inicio no disponible");
    }
    await cache.put(inicio, html);
    try {
      const respuesta = await fetch(manifest);
      if (respuesta.ok) {
        const datos = await respuesta.clone().json();
        await cache.put(manifest, respuesta);
        await Promise.allSettled((datos.icons || []).map(icono => {
          const url = new URL(icono.src, manifest);
          if (url.origin === raiz.origin && url.pathname.startsWith(raiz.pathname)) return cache.add(url.href);
        }));
      }
    } catch { /* El HTML incluye la interfaz incluso sin iconos de instalación. */ }
    await self.skipWaiting();
  })());
});
self.addEventListener("activate", evento => {
  evento.waitUntil((async () => {
    for (const clave of await caches.keys()) {
      if (clave.startsWith(prefijo) && clave !== nombre) await caches.delete(clave);
    }
    await self.clients.claim();
  })());
});
self.addEventListener("fetch", evento => {
  const solicitud = evento.request;
  const url = new URL(solicitud.url);
  if (solicitud.method !== "GET" || url.origin !== raiz.origin || !url.pathname.startsWith(raiz.pathname)) return;
  const esInicio = solicitud.mode === "navigate" && [raiz.pathname,new URL(inicio).pathname].includes(url.pathname);
  if (esInicio) {
    evento.respondWith((async () => {
      const cache = await caches.open(nombre);
      try {
        const respuesta = await fetch(solicitud);
        if (respuesta.ok || respuesta.status < 500) return respuesta;
        return await cache.match(inicio) || respuesta;
      } catch {
        return await cache.match(inicio) || Response.error();
      }
    })());
    return;
  }
  // Las comprobaciones de Internet y las solicitudes de sincronización deben llegar a la red.
  if (url.search || !(url.href === manifest || url.pathname.startsWith(raiz.pathname + "icons/"))) return;
  evento.respondWith((async () => {
    const cache = await caches.open(nombre);
    const copia = await cache.match(solicitud);
    if (copia) return copia;
    const respuesta = await fetch(solicitud);
    if (respuesta.ok) await cache.put(solicitud,respuesta.clone());
    return respuesta;
  })());
});
