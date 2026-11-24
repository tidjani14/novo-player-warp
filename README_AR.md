# مشروع NOVO PLAYER 3.8 + Cloudflare WARP

هذا المشروع يعيد بناء أرشيف Smali المفكوك المرفق، ويضيف شاشة WARP ثانية داخل نفس APK. الاتصال يتم من Android مباشرة إلى نقطة تسجيل Cloudflare الموجودة في منطق PHP المرفق، ثم يمرر الإعداد إلى مكتبة WireGuard الرسمية (GoBackend). لا يحتاج PHP مستضافًا، ولا يستخدم ملف OpenVPN أو شهادات وهمية.

## طريقة البناء في GitHub

1. أنشئ مستودعًا جديدًا وارفع **كل محتويات هذا المجلد** إليه، مع ملف `NOVO_PLAYER_v3.8(40)_base_src.zip`.
2. من GitHub افتح **Actions** وشغّل `Build NOVO Player with Cloudflare WARP` عبر **Run workflow**. يمكن تشغيله تلقائيًا كذلك بعد دفع تغييرات للمسارات المحددة.
3. بعد نجاح البناء، افتح تشغيل workflow ونزّل artifact باسم `NOVO-PLAYER-WARP-APK`.
4. لو لم تضبط أسرار التوقيع، ينتج APK موقّعًا بمفتاح CI مؤقت للتجربة. للتحديث المستمر، أضف GitHub Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

السكربت الأساسي هو `scripts/build_github.sh`؛ تقدر تشغّله على Ubuntu عليه Java 17 وGradle وAndroid SDK وPython وcurl وunzip.

## أين تظهر الشاشة؟

الـ manifest يضيف مدخل Launcher ثانيًا باسم **Cloudflare WARP**. الضغط عليه يفتح شاشة الاتصال داخل نفس APK. Android سيطلب موافقة VPN أول مرة، وهذا مطلوب من النظام.

## تنبيهات مهمة

- نسخة التطبيق المفكوكة تعلن `minSdkVersion 21`. لذلك يعتمد المشروع إصدار WireGuard `1.0.20250531` الذي يحافظ على التوافق مع API 21؛ رفع المكتبة لإصدارات أحدث قد يرفع الحد الأدنى للنظام.
- عنوان التسجيل `api.cloudflareclient.com/v0a2158/reg` واجهة غير موثقة رسميًا، مأخوذة من PHP الذي أرسلته. قد تتغير من Cloudflare؛ النفق يستخدم WireGuard حقيقيًا بعد استلام إعداد ناجح منها.
- سيتم توقيع الناتج بمفتاح جديد. لا يمكن تثبيته كتحديث فوق نسخة لها توقيع مختلف؛ احذف النسخة القديمة أو استعمل مفتاح التوقيع الأصلي إن كان متاحًا لديك.
- التطبيق الأصلي مفكوك من APK وليس مشروع المصدر الأصلي. هذا يعيد بناء الموارد وSmali ثم يضيف شاشة WARP؛ ليس استعادة للمصدر Kotlin/Java غير الموجود.
- نفق WARP يوجّه كل حركة الجهاز IPv4/IPv6 عبر `AllowedIPs = 0.0.0.0/0, ::/0`.


## Smart WARP routing

- Uses Cloudflare registration's WireGuard ports and documented defaults/fallbacks: UDP 2408, 500, 1701, and 4500.
- Tests each candidate with a temporary WireGuard tunnel, verifies `warp=on` / `warp=plus` through Cloudflare trace, and measures a small download sample before reconnecting on the best verified port.
- Remembers the preferred UDP port separately for Wi-Fi and mobile data.
- Caches the registration and private key encrypted with Android Keystore AES-GCM on Android 6.0+; Android 5.x keeps it in memory only.
- Compares at least two candidates; if a path is already at or below 180 ms and at least 8 Mbps, it skips remaining probes.
- Cloudflare Anycast and ISP peering determine the data center; the app optimizes supported transport ports but cannot force a geographic colo.
