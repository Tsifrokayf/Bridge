# Bridge v1 — протокол

Собственный протокол поверх TCP в локальной сети. Не совместим с Quick Share, KDE Connect и AirDrop.
Эталонная реализация — `reference/bridge_proto.py`; Kotlin и Swift проверяются на общих тестовых векторах
`vectors/vectors.json`.

## Роли и обнаружение

* Mac — сервер (TCP-listener на произвольном порту), Android — клиент с постоянным соединением.
* Mac анонсирует Bonjour/mDNS-сервис `_bridgesync._tcp`, TXT: `v=1`, `id=<device id Mac, hex>`.
  Mac слушает TCP-порт 47653 (если занят — любой свободный). Если mDNS недоступен (Wi-Fi ⇄ Ethernet, роутер не пропускает multicast),
  Android перебирает хосты своей подсети (до /22) на порту 47653 и пробует подключиться; при одном найденном Mac сопряжение стартует само.
  Остаётся ввод `адрес[:порт]` вручную.

## Кадры

`[uint32 BE длина][тело]`, длина 1…2 MiB. До конца рукопожатия тело — открытый текст, потом — шифртекст записи.

## Рукопожатие

Все ключи — эфемерные P-256 (ECDH), открытый ключ — 65 байт (несжатая точка SEC1). Подписей нет: аутентичность
даёт либо сверка короткого кода (сопряжение), либо общий секрет (переподключение).

| Тип | Код | Состав |
|---|---|---|
| PAIR_HELLO | 0x01 | ver(1) · devid(16) · pub(65) · nonce(32) · namelen(1) · name |
| RECONNECT_HELLO | 0x02 | ver(1) · devid(16) · pub(65) · nonce(32) — 115 байт |
| RECONNECT_REPLY | 0x03 | pub(65) · nonce(32) · mac(32) — 130 байт |
| RECONNECT_FINISH | 0x04 | mac(32) — 33 байта |
| PAIR_REPLY | 0x05 | devid(16) · pub(65) · nonce(32) · namelen(1) · name |

(каждое тело начинается с байта типа; имя — UTF-8, не больше 64 байт.)

### Сопряжение

1. Клиент → PAIR_HELLO, сервер (только в режиме сопряжения) → PAIR_REPLY.
2. `Z = ECDH`, `th = SHA256(hello ‖ reply)`.
3. `okm = HKDF-SHA256(ikm = Z, salt = th, info = "bridge-v1 pair", 100 байт)`:
   * `0..32` — ключ клиент→сервер, `32..64` — сервер→клиент;
   * `64..68` — SAS: `uint32 BE % 1 000 000`, 6 цифр с ведущими нулями (показывается на обоих экранах);
   * `68..100` — общий секрет сопряжения.
4. Оба пользователя сверяют SAS; каждый шлёт зашифрованное `pair_confirm`. Сопряжение сохраняется,
   только когда подтвердили обе стороны. До этого сервер отбрасывает всё, кроме `hello`/`pair_confirm`/`ping`.

Защита от подмены — сверка SAS: активный атакующий получил бы разные коды на двух сторонах.

### Переподключение

1. Клиент → RECONNECT_HELLO. Сервер ищет секрет по `devid`, иначе обрывает соединение.
2. `th = SHA256(hello ‖ server_pub ‖ server_nonce)`.
3. Сервер → REPLY с `mac = HMAC-SHA256(secret, "bridge-v1 server" ‖ th)`; клиент проверяет.
4. Клиент → FINISH с `mac = HMAC-SHA256(secret, "bridge-v1 client" ‖ th)`; сервер проверяет (постоянное время).
5. `okm = HKDF-SHA256(ikm = Z ‖ secret, salt = th, info = "bridge-v1 keys", 64 байта)`: `0..32` клиент→сервер,
   `32..64` сервер→клиент. Эфемерные ключи дают прямую секретность.

## Зашифрованные записи

AES-256-GCM, nonce = `00 00 00 00 ‖ счётчик(8, BE)` (свой счётчик на каждое направление, с нуля), без AAD.
Открытый текст: `тип(1) ‖ тело`.

* `0x01` JSON (UTF-8);
* `0x02` кусок файла: `id(4, BE) ‖ данные` (до 256 KiB).

Любая ошибка расшифровки (в том числе повтор или пропуск записи) закрывает соединение.

## JSON-сообщения (поле `t`)

| `t` | Поля | Примечание |
|---|---|---|
| `hello` | `name`, `platform`, `caps` | сразу после рукопожатия, с обеих сторон |
| `pair_confirm` | `ok` | только при сопряжении |
| `ping` / `pong` | — | Android пингует раз в 15 с, нет трафика 45 с — соединение мертво (Mac ждёт 60 с) |
| `clipboard` | `text`, `ts` | |
| `notification` | `key`, `app`, `pkg`, `title`, `text`, `ts` | Android → Mac |
| `notification_removed` | `key` | |
| `file_offer` | `id`, `name`, `size` | |
| `file_accept` / `file_reject` | `id` (+`reason`) | |
| `file_done` | `id`, `sha256` | после всех кусков |
| `file_ack` | `id`, `ok` | размер и SHA-256 совпали |
| `file_cancel` | `id` | |

### Расширения v1.1

Все — обычные JSON-сообщения внутри зашифрованного канала; сторона, которая их не знает, их игнорирует.
Поддерживаемые возможности перечислены в `caps` сообщения `hello`
(`clipboard, notifications, files, reply, status, find, media, sms, calls, photos, dial, contacts, fs, clipimg`).

| `t` | Направление | Поля | Примечание |
|---|---|---|---|
| `notification` (доп. поля) | A → M | `reply`, `clear` | есть ли inline-ответ; можно ли смахнуть удалённо |
| `notification_reply` | M → A | `key`, `text` | телефон вызывает RemoteInput-действие уведомления |
| `notification_dismiss` | M → A | `key` | телефон смахивает уведомление |
| `status` | A → M | `battery` (0–100), `charging`, `net` (`wifi`/`mobile`/`none`) | при подключении и при изменениях |
| `find_phone` | M → A | `on` | громкий сигнал на телефоне (до 2 минут или пока не выключат) |
| `media` | M → A | `action`: `play_pause`/`next`/`previous`/`volume_up`/`volume_down` | |
| `sms` | A → M | `id`, `from`, `name`?, `text`, `ts` | входящее SMS |
| `sms_send` | M → A | `to`, `text`, `req` | отправить SMS с телефона |
| `sms_result` | A → M | `req`, `ok` | |
| `call` | A → M | `state` (`ringing`/`offhook`/`idle`), `number`?, `name`? | |
| `mirror_start` | M → A | `w` (width px), `fps`, `q` (JPEG quality) | Starts screen mirroring; the phone asks the user for consent |
| `mirror_stop` | M → A | | |
| `mirror_state` | A → M | `state`: `asking` \| `on` \| `off` \| `denied`; `control` (bool) | `control` = accessibility service enabled |
| `mirror_frame` | A → M | `w`, `h`, `seq`, `d` (base64 JPEG) | At most 3 frames unacknowledged |
| `mirror_ack` | M → A | `seq` | Back-pressure for frames |
| `mirror_input` | M → A | `kind`: `tap` (`x`,`y`,`ms`) \| `swipe` (`x1`,`y1`,`x2`,`y2`,`ms`) \| `pinch` (`x`,`y`,`in`) \| `key` (`back`,`home`,`recents`,`lock`,`backspace`,`enter`) \| `text` | Coordinates normalised 0..1 |
| `threads_request` | M → A | | SMS conversation list (needs READ_SMS) |
| `sms_threads` | A → M | `items`: `tid`, `address`, `name`, `snippet`, `ts`, `out`, `unread`; `error`: `perm` | Up to 40 newest conversations |
| `thread_request` | M → A | `tid` | |
| `sms_thread` | A → M | `tid`, `items`: `text`, `ts`, `out` | Last 80 messages, oldest first |
| `mirror_audio` | A → M | `rate` (24000), `d` (base64 PCM16 mono) | Media/game audio of the phone while mirroring; calls cannot be captured |
| `sms_search` | M → A | `q` (≥ 2 chars) | Answer is `sms_threads` with `search: true` |
| `mac_status` | M → A | `battery`, `charging` | Sent every 30 s |
| `pointer` | A → M | `dx`, `dy` | Trackpad mode; Mac needs the Accessibility permission |
| `click` | A → M | `button`: `left` \| `right`, `count` (1–3) | |
| `scroll` | A → M | `dx`, `dy` (pixels) | |
| `mac_key` | A → M | `key`: `backspace`, `enter`, `esc`, `tab`, arrows | |
| `mac_text` | A → M | `text` | Typed into the focused Mac app |
| `mac_media` | A → M | `action`: `play_pause`, `next`, `previous`, `volume_up`, `volume_down`, `mute` | |
| `find_mac` | A → M | `on` (bool) | Mac plays a loud sound for up to 60 s |
| `lock_mac` | A → M | | Mac puts the display to sleep (locks with "require password") |
| `open_url` | both | `url` (http/https only) | Handoff-style link; receiver shows a notification and opens it on tap |
| `dnd` | A → M | `on` (bool), `canSet` (bool) | Phone's Do Not Disturb state; sent on connect and on change |
| `dnd_set` | M → A | `on` (bool) | Turns phone DND on/off (needs notification-policy access) |
| `call_action` | M → A | `action`: `accept` \| `reject` \| `hangup` \| `speaker` \| `mute`; `on` (bool) for speaker/mute | Controls the call on the phone; call audio stays on the phone |
| `file_offer` (доп. поле) | A → M | `kind: "photo"` | Mac кладёт такой файл в папку фото, а не в «Загрузки/Bridge» |

### Расширения v1.2

| `t` | Направление | Поля | Примечание |
|---|---|---|---|
| `dial` | M → A | `number` (`[+0-9*#() -]`, до 32 символов) | позвонить с телефона; нужны включённые «SMS и звонки» и разрешение CALL_PHONE |
| `contacts_request` / `contacts` | M → A / A → M | `ok`, `items`: `[{n, p}]` (до 2000) | контакты с номерами |
| `calls_request` / `calls` | M → A / A → M | `limit` (1–100) / `ok`, `items`: `[{number, name?, type: in/out/missed/other, ts, dur}]` | журнал звонков |
| `fs_list` / `fs_list_result` | M → A / A → M | `req`, `path` (относительно общей памяти, `/`) / `ok`, `error`?, `entries`: `[{name, dir, size, mtime}]` | только чтение; нужен переключатель на телефоне и «доступ ко всем файлам» |
| `fs_get` / `fs_get_result` | M → A / A → M | `req`, `path` / `ok`, `error`? | при `ok` телефон шлёт файл через `file_offer` с `kind: "pull"` |
| `file_offer` (доп. значение) | любая | `kind: "clipimg"` | картинка из буфера обмена; Mac кладёт её в буфер, телефон сохраняет как файл |

Ошибки `fs_*`: `disabled` (выключено/нет доступа), `path` (путь вне корня общей памяти — проверяется по каноническому пути),
`unreadable`.

Пока сопряжение не подтверждено на Mac, Mac отбрасывает всё из этой таблицы и отклоняет файлы.

Неизвестные сообщения игнорируются (совместимость вперёд). Принимающая сторона автоматически принимает файлы
от сопряжённого устройства, берёт у имени только последний сегмент пути, заменяет управляющие символы и
`<>:"|?*` на `_`, обрезает пробелы/точки по краям, ограничивает 200 символами и никогда не перезаписывает
существующие файлы.

## Не входит в v1

SMS, звонки, управление мультимедиа, удалённый ввод, синхронизация контактов/фото, несколько Mac у одного телефона.

## Bluetooth LE (без общей сети)

По образцу Apple Continuity: BLE нужен для обнаружения и небольших сообщений, большие данные идут по Wi-Fi.

* Телефон — GATT-сервер и рекламирует сервис `B21D0001-5E1A-4C7B-9A3F-0D6F62726964`; в scan response — service data `0xB21D` из 6 байт:
  `HMAC-SHA256(pairingSecret, метка "bridge-ble-v1" ‖ slot BE Int64)[0..6]`, где `slot = unixtime / 900`.
  Пока идёт сопряжение, токен нулевой (Mac принимает его только при открытом «Сопрячь телефон…»).
* Mac — GATT-клиент (CoreBluetooth): узнаёт токен известного телефона (слоты −1…+1), подключается, подписывается на
  `B21D0003-…` (notify, телефон → Mac) и пишет в `B21D0002-…` (write without response, Mac → телефон).
* Поверх этой пары характеристик идёт тот же поток кадров и то же рукопожатие, что и по TCP; телефон — клиент рукопожатия.
* Поверх BLE не работают зеркало экрана (`mirror_state: nonet`) и синхронизация фото. Как только Mac доступен по IP,
  телефон закрывает BLE-сессию и подключается по TCP.


## Now playing and phone location

- `now_playing` (phone → Mac): `{title, artist, app, playing}`; empty `title` means nothing is playing. Sent on every change. Controls
  go back through the existing `media` message (`play_pause`, `next`, `previous`, …). Capability `nowplaying`.
- `now_playing_request` (Mac → phone): ask the phone to re-send the current state.
- `locate_phone` (Mac → phone) → `phone_location` (phone → Mac): `{lat, lon, acc, ts}` (`ts` in ms) or `{error: "permission"|"unavailable"}`.
  Capability `locate`. The phone answers with a fix younger than two minutes, otherwise asks for a fresh one (best effort in the background).

## Ringer, camera, auto-lock

- `ringer_set` (Mac → phone): `{mode: "normal"|"vibrate"|"silent"}`; the phone answers (and reports every change) with `ringer` `{mode}`. Capability `ringer`.
- `camera_request` (Mac → phone): opens the phone camera (heads-up notification, since Android only lets an app in the foreground
  open it); the picture is sent back as a file transfer of kind `camera`. Capability `camera`.
- Auto-lock is Mac-only and off by default: when the last phone session has been gone for 60 s the Mac locks itself.

## Power and volume (phone → Mac)

- `mac_power` `{action: "sleep"}`: the Mac goes to sleep (it announces `mac_state: sleep` first, so the phone stops searching and may send Wake-on-LAN later).
- `mac_volume` `{action: "mute_toggle"|"up"|"down"}`.

## Mac-only helpers (no new messages)

"Open browser tab on phone" (address of the front tab of the browser used last, through AppleScript) uses `open_url`; "Screenshot area → phone" sends a normal file.

## Agenda

`phone_agenda` (phone → Mac): `{events: [{title, start, end, allDay, place}], alarm, calendar}` (times in ms; `alarm` = next alarm or 0). Sent on connect and every
15 minutes, and on `agenda_request` (Mac → phone). Needs the calendar permission on the phone.

## Where is my Mac

`locate_mac` (phone → Mac) → `mac_location` (Mac → phone): `{lat, lon, acc}` or `{error: "permission"|"unavailable"}`. The phone shows a notification that opens the map.
"Route on phone": the Mac sends an `open_url` with a maps directions URL.

## Mac screenshot, volume, battery

`mac_screenshot` (phone → Mac): the Mac captures the whole screen and sends it back as an ordinary file transfer. `mac_volume` also accepts `up`/`down`. The phone raises a notification when the Mac's battery (from `mac_status`) drops to 15% or lower while not charging.
