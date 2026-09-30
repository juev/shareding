# ShareDing privacy policy

Effective September 30, 2026.

ShareDing is an Android app that saves bookmarks on your device and sends them to the linkding server you configure. Questions about this policy can be sent to [denis@evsyukov.org](mailto:denis@evsyukov.org).

## Data handled by the app

ShareDing stores queued bookmark URLs, titles, descriptions, notes, tags, unread and archive choices, and delivery status on your device. It also stores your linkding server URL, default settings, the most recent sync time and error, and any proxy host, port, and username you enter. The linkding API token and optional proxy password are encrypted on the device using a key held by Android Keystore.

When sync runs, ShareDing sends the bookmark fields and API token to your configured linkding server. The server URL must use HTTPS. Your linkding server's operator determines how data sent to that server is stored and used; consult that server's policy if you do not operate it yourself. ShareDing has no developer-operated service that receives your bookmarks or token.

If you tap **Fetch page details**, ShareDing may request the bookmarked page to read its title, description, and keyword tags. Background sync leaves page metadata retrieval to linkding and omits titles received through Share. Titles accepted in the Add Bookmark form are sent to linkding. Page-detail requests do not include your linkding API token. Bookmarked pages may use HTTP or HTTPS. An HTTP page request and its response are unencrypted, and the page operator can receive your IP address and ordinary request information.

## Optional proxy

If you enable a proxy, ShareDing sends linkding requests and page-detail requests through the HTTP proxy you configure. The proxy can see the destination and connection metadata. An HTTPS linkding connection uses TLS through the proxy, so the proxy does not receive the linkding API token or bookmark content from that connection. HTTP page requests and their responses are visible to the proxy.

If you configure a proxy username and password, HTTP Basic authentication sends those credentials to the proxy without TLS, including when the linkding connection itself uses HTTPS. Use a proxy and network you trust. ShareDing does not fall back to a direct connection when the configured proxy fails.

## Retention and control

Queued bookmarks remain on your device until linkding accepts them or you delete them from the queue. Settings remain until you change them or uninstall the app. Uninstalling removes the app's local data; Android backup and device-to-device extraction are disabled for it. Deleting a queued bookmark does not delete any bookmark already stored on your linkding server. Manage delivered bookmarks and their retention on that server.

ShareDing does not include advertising or analytics SDKs. Google Play and Android may process installation, update, crash, and device information under their own terms; that processing is separate from ShareDing's bookmark and connection handling.

We will update this page if the app's data handling changes.
