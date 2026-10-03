// Aether service worker: shows Web Push notifications for agent runs (needs input / finished)
// and opens the run when the notification is tapped.
self.addEventListener('push', function(event)
{
  var data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) { data = { title: 'Aether', body: event.data ? event.data.text() : '' }; }
  event.waitUntil(self.registration.showNotification(data.title || 'Aether', {
    body: data.body || '',
    tag: data.tag || 'aether-run',
    renotify: true,
    icon: '/images/Aether.ing.png',
    data: { url: data.url || '/' }
  }));
});

self.addEventListener('notificationclick', function(event)
{
  event.notification.close();
  var url = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function(list)
  {
    // Reuse an open Aether tab when the browser allows it; otherwise open a new one
    for (var i = 0; i < list.length; i++)
    {
      if ('navigate' in list[i])
      {
        var client = list[i];
        return client.navigate(url).then(function(c) { return (c || client).focus(); }).catch(function() { return clients.openWindow(url); });
      }
    }
    if (clients.openWindow) return clients.openWindow(url);
  }));
});
