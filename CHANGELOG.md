# Changelog — سجل التحديثات

## v1.0.0 — 2026-09-30

### English

- **Rebrand `powerk` → `power-strip`**: new Android package `com.powerstrip.app`,
  new plug-and-bolt logo, server file `power-strip.py`, new endpoints
  (`/power-strip.py`, `/power-strip.apk`), renamed scripts and docs.
  One-time cost: uninstall the old app and re-enter server settings.
- **Custom names**: rename any strip or outlet from the app (tap a name) or the
  API (`GET/POST /api/names`). Stored on the server, shared by web UI and app.
- **Energy history & reports**: per-minute samples per outlet (30-day retention,
  auto-prune, reboot-safe counters), `GET /api/history` (raw/hour/day buckets),
  `GET /api/report` (today/week/month with totals, per-outlet kWh, peak/min
  power with timestamps), and a Reports tab with a power curve.
- **Automation scenes**: power-threshold (above/below watts held for N seconds),
  time schedules (HH:MM + weekdays), or manual one-tap runs — executed on the
  server (works while the phone sleeps) with anti-flap sustain checks and a
  60-second cooldown. Full API: list/create/update/delete/run.
- **Favorites**: phone-local ordered list of outlets across strips, with
  reorder/add/remove management, shown on top of the Plugs tab.
- **Link from anywhere in the flow**: in-app strip provisioning over raw TCP
  (pinned to Wi-Fi so mobile data can't steal it), plus web-based provisioning
  from the browser (Link card with Test/Provision, validation, clear errors).
- **Easy backend on Windows**: one-click `run-power-strip.bat` /
  `stop-power-strip.bat`, and `install-service.bat` for an auto-start
  background service (restart on crash) with `uninstall-service.bat`.
- **Security**: token auth for UI/API, brute-force rate limiting (10/min → 429),
  strict input validation everywhere (IPv4, MACs, no `:` smuggling).

### العربية

- **إعادة التسمية `powerk` ← `power-strip`**: حزمة أندرويد جديدة
  `com.powerstrip.app`، لوجو قابس وصاعقة، ملف الخادم `power-strip.py`، وروابط
  جديدة (`/power-strip.py`، `/power-strip.apk`) وسكربتات وتوثيق بأسماء جديدة.
  الثمن لمرة واحدة: حذف التطبيق القديم وإعادة إدخال الإعدادات.
- **أسماء مخصصة**: دوس على أي اسم مشترك أو فيشة لتعديله (من التطبيق أو عبر
  `GET/POST /api/names`). تُحفظ في الخادم وتظهر في الويب والتطبيق معًا.
- **سجل الطاقة والتقارير**: عينة كل دقيقة لكل فيشة (احتفاظ 30 يومًا، تنظيف
  تلقائي، وتحمّل تصفير العداد)، و`GET /api/history` (خام/ساعة/يوم)، و
  `GET /api/report` (يوم/أسبوع/شهر: الإجمالي وkWh لكل فيشة والذروة/الحضيض مع
  وقتهما)، وتبويب تقارير بمنحنى قدرة.
- **سيناريوهات الأتمتة**: عتبة قدرة (فوق/تحت واط لعدد ثوانٍ)، مواعيد (HH:MM +
  أيام)، أو تنفيذ يدوي — تُنفَّذ في الخادم (تعمل والهاتف مغلق) مع منع التذبذب
  وتهدئة 60 ثانية. API كامل: عرض/إنشاء/تحديث/حذف/تنفيذ.
- **المفضلة**: قائمة مرتبة في الهاتف لفيش من مشتركات مختلفة، مع
  تحريك/إضافة/حذف، وتظهر أعلى تبويب الفيش.
- **الربط من كل مكان في التدفق**: ربط المشترك من التطبيق عبر TCP مباشر
  (مثبت على الواي فاي حتى لا تخطفه بيانات الهاتف)، وربط من المتصفح (بطاقة
  ربط بزر اختبار/تنفيذ وتحقق ورسائل واضحة).
- **تشغيل سهل على ويندوز**: `run-power-strip.bat` بدبل-كليك و
  `stop-power-strip.bat`، و`install-service.bat` لخدمة خلفية تبدأ مع الإقلاع
  وتعيد التشغيل عند التعطل، مع `uninstall-service.bat`.
- **الأمان**: رمز للواجهة والـ API، وحد لمحاولات التخمين (10/دقيقة ← 429)،
  وتحقق صارم من كل المدخلات (IPv4 وMACs ومنع تمرير `:`).
