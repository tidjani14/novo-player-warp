# رفع المشروع وبناء APK تلقائيًا

1. ثبّت المتطلبات في Termux: `pkg install git gh unzip`.
2. فك الحزمة المحدّثة إلى مجلد Downloads، ثم شغّل `bash upload_to_github.sh` من داخل `novo_warp_github`.
3. سجّل الدخول إلى GitHub عند الطلب. يبحث السكربت عن مستودع المشروع السابق ويحدّثه؛ وإذا لم يجده، ينشئ مستودعًا خاصًا جديدًا. ينفذ Git داخل مجلد Termux الخاص لتجنب قيود مجلد Downloads.
4. يبدأ GitHub Actions ببناء المحرك والتطبيق. افتح تبويب Actions، وبعد اكتمال التشغيل نزّل artifact باسم `NOVO-PLAYER-MASQUE-APK`.
