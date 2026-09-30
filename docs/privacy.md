# ShareDing privacy policy

Effective September 30, 2026.

ShareDing is an Android app that saves bookmarks on your device and sends them to the linkding server you configure. Questions about this policy can be sent to [denis@evsyukov.org](mailto:denis@evsyukov.org).

## Data handled by the app

ShareDing stores queued bookmark URLs, titles, descriptions, notes, tags, unread and archive choices, and delivery status on your device. It also stores your linkding server URL, default settings, and the most recent sync time and error. The linkding API token is encrypted on the device using a key held by Android Keystore.

When sync runs, ShareDing sends the bookmark fields and API token to your configured linkding server. The server URL must use HTTPS. Your linkding server's operator determines how data sent to that server is stored and used; consult that server's policy if you do not operate it yourself. ShareDing has no developer-operated service that receives your bookmarks or token.

If you tap **Fetch page details**, ShareDing may request the bookmarked page to read its title and description. Background sync leaves page metadata retrieval to linkding and omits titles received through Share. Titles accepted in the Add Bookmark form are sent to linkding. Page-detail requests do not include your linkding API token. Bookmarked pages may use HTTP or HTTPS. An HTTP page request and its response are unencrypted, and the page operator can receive your IP address and ordinary request information.

If a queued link comes from a known URL shortener such as `t.co`, `bit.ly`, or `vk.cc`, the sync worker requests that short link from the shortener to learn its target before sending the bookmark. The request contains the short link but no linkding API token or other bookmark fields. The shortener operator can receive your IP address and ordinary request information. ShareDing does not open the target page; the short link is added to the bookmark notes sent to linkding.

ShareDing can show local notifications about sync problems and links that have waited more than a day. They are created on your device and are not sent anywhere.

## Network routes

ShareDing connects directly to your linkding server and to bookmarked pages. It has no proxy setting and ignores a system HTTP proxy. If you use a VPN on your device, Android sends ShareDing's connections through it like other app traffic. Versions before 0.2.1 offered an optional HTTP proxy; updating to a version without it deletes any saved proxy host, port, username, and password from the device.

## Retention and control

Queued bookmarks remain on your device until linkding accepts them or you delete them from the queue. Settings remain until you change them or uninstall the app. Uninstalling removes the app's local data; Android backup and device-to-device extraction are disabled for it. Deleting a queued bookmark does not delete any bookmark already stored on your linkding server. Manage delivered bookmarks and their retention on that server.

ShareDing does not include advertising or analytics SDKs. Google Play and Android may process installation, update, crash, and device information under their own terms; that processing is separate from ShareDing's bookmark and connection handling.

We will update this page if the app's data handling changes.
