<?php
declare(strict_types=1);
require_once __DIR__ . '/vpn_access_store.php';

$secure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off');
session_set_cookie_params([
    'httponly' => true,
    'secure' => $secure,
    'samesite' => 'Strict',
]);
session_start();
header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store, max-age=0');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');
header('Permissions-Policy: camera=(), microphone=(), geolocation=()');

function h(string $value): string { return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
function csrfToken(): string
{
    if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(32));
    return (string)$_SESSION['csrf'];
}
function verifyCsrf(): void
{
    $sent = (string)($_POST['csrf'] ?? '');
    if ($sent === '' || !hash_equals((string)($_SESSION['csrf'] ?? ''), $sent)) {
        http_response_code(400);
        throw new RuntimeException('انتهت صلاحية النموذج. حدّث الصفحة وحاول مرة أخرى.');
    }
}

$hash = vpnAdminPasswordHash();
$error = '';
$notice = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    try {
        verifyCsrf();
        $action = (string)($_POST['action'] ?? '');
        if ($action === 'login') {
            if ($hash === '') throw new RuntimeException('اضبط كلمة مرور لوحة التحكم في vpn_access_config.php أولاً.');
            $attempts = (int)($_SESSION['login_attempts'] ?? 0);
            if ($attempts >= 8 && time() - (int)($_SESSION['last_login_attempt'] ?? 0) < 300) {
                throw new RuntimeException('محاولات كثيرة. انتظر خمس دقائق ثم أعد المحاولة.');
            }
            $_SESSION['last_login_attempt'] = time();
            if (!password_verify((string)($_POST['password'] ?? ''), $hash)) {
                $_SESSION['login_attempts'] = $attempts + 1;
                throw new RuntimeException('كلمة المرور غير صحيحة.');
            }
            session_regenerate_id(true);
            $_SESSION['authenticated'] = true;
            $_SESSION['login_attempts'] = 0;
            $_SESSION['csrf'] = bin2hex(random_bytes(32));
            header('Location: ' . strtok($_SERVER['REQUEST_URI'] ?? 'vpn_access_admin.php', '?'));
            exit;
        }

        if (empty($_SESSION['authenticated'])) throw new RuntimeException('سجّل الدخول أولاً.');
        if ($action === 'logout') {
            $_SESSION = [];
            session_destroy();
            header('Location: ' . strtok($_SERVER['REQUEST_URI'] ?? 'vpn_access_admin.php', '?'));
            exit;
        }
        if ($action === 'save') {
            $mac = vpnSaveDevice(
                (string)($_POST['mac'] ?? ''),
                (($_POST['allowed'] ?? '0') === '1'),
                (string)($_POST['expires_at'] ?? '') ?: null,
                (string)($_POST['note'] ?? '')
            );
            $notice = 'تم حفظ صلاحية VPN للجهاز ' . $mac . '.';
        }
        if ($action === 'change_password') {
            $currentPassword = (string)($_POST['current_password'] ?? '');
            $newPassword = (string)($_POST['new_password'] ?? '');
            $confirmPassword = (string)($_POST['confirm_password'] ?? '');
            $currentHash = vpnAdminPasswordHash();
            if ($currentHash === '' || !password_verify($currentPassword, $currentHash)) {
                throw new RuntimeException('كلمة المرور الحالية غير صحيحة.');
            }
            if (strlen($newPassword) < 12) {
                throw new RuntimeException('اختر كلمة مرور جديدة لا تقل عن 12 خانة.');
            }
            if (strlen($newPassword) > 200) {
                throw new RuntimeException('كلمة المرور طويلة جدًا.');
            }
            if (!hash_equals($newPassword, $confirmPassword)) {
                throw new RuntimeException('تأكيد كلمة المرور الجديدة غير مطابق.');
            }
            $newHash = password_hash($newPassword, PASSWORD_DEFAULT);
            if (!is_string($newHash) || $newHash === '') throw new RuntimeException('تعذر إنشاء كلمة المرور الجديدة.');
            vpnSetAdminPasswordHash($newHash);
            session_regenerate_id(true);
            $_SESSION['csrf'] = bin2hex(random_bytes(32));
            $notice = 'تم تغيير كلمة مرور الإدارة بنجاح.';
        }
    } catch (Throwable $exception) {
        $error = $exception->getMessage();
    }
}

$authenticated = !empty($_SESSION['authenticated']);
$devices = [];
if ($authenticated) {
    try { $devices = vpnListDevices(); }
    catch (Throwable $exception) { $error = $exception->getMessage(); }
}
$allowedCount = 0;
if ($authenticated) {
    foreach ($devices as $mac => $device) if (vpnIsAllowed((string)$mac)) $allowedCount++;
}
$token = csrfToken();
?>
<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b0b0b">
<title>إدارة صلاحيات VPN — NOVO</title>
<style>
:root{color-scheme:dark;--bg:#090909;--surface:#121212;--surface2:#171717;--line:#302819;--gold:#d2ad5c;--gold2:#9a7738;--text:#f5f1e8;--muted:#a9a394;--red:#ff9c91;--green:#b5dc9e;--blue:#c4d8ec;--radius:20px;--shadow:0 18px 55px #0007}
*{box-sizing:border-box}html{min-height:100%;background:var(--bg)}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at 82% -8%,#332713 0,transparent 35%),linear-gradient(180deg,#0c0c0c,#080808 55%);color:var(--text);font:16px/1.55 system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}button,input,select{font:inherit}button{min-height:46px;border:1px solid #b89545;background:linear-gradient(135deg,#d2ad5c,#a77f35);color:#17130a;border-radius:12px;padding:11px 18px;font-weight:800;cursor:pointer;transition:transform .15s,filter .15s}button:hover{filter:brightness(1.08);transform:translateY(-1px)}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #d2ad5c66;outline-offset:2px}.secondary{background:#1b1915;color:var(--gold);border-color:#57462a}.danger{background:#2a1715;color:var(--red);border-color:#63372f}.wrap{width:min(1120px,calc(100% - 36px));margin:0 auto;padding:32px 0 60px}.topbar{display:flex;justify-content:space-between;align-items:center;gap:18px;margin-bottom:24px}.identity{display:flex;align-items:center;gap:14px}.mark{display:grid;place-items:center;width:48px;height:48px;border:1px solid #715a2e;border-radius:15px;background:linear-gradient(145deg,#292113,#111);color:var(--gold);font-size:22px;box-shadow:0 8px 28px #0006}.brand{color:var(--gold);font-size:14px;font-weight:850;letter-spacing:.12em}.heading{margin:2px 0 0;font-size:clamp(22px,4vw,30px);line-height:1.25}.sub{color:var(--muted);margin:5px 0 0;font-size:14px}.top-actions{display:flex;align-items:center;gap:10px}.status-chip{border:1px solid #403522;color:var(--gold);border-radius:99px;padding:7px 11px;font-size:12px;font-weight:750;white-space:nowrap}.card{background:linear-gradient(145deg,#171717,#101010);border:1px solid var(--line);border-radius:var(--radius);padding:clamp(18px,3vw,26px);box-shadow:var(--shadow);margin:18px 0}.section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:18px}.section-head h2{font-size:19px;margin:0}.section-head p{margin:5px 0 0}.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.field{min-width:0;display:flex;flex-direction:column;gap:7px}.field label{font-size:13px;color:#c3bba9;font-weight:650}.field input,.field select,.search{min-width:0;width:100%;min-height:48px;background:#0a0a0a;color:var(--text);border:1px solid #403724;border-radius:12px;padding:12px 13px;line-height:1.3}.field input::placeholder,.search::placeholder{color:#787367}.field input:focus,.field select:focus,.search:focus{border-color:var(--gold2)}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:17px}.message{border-radius:12px;padding:13px 15px;margin:14px 0}.err{background:#351b19;color:#ffd5d0;border:1px solid #63342f}.ok{background:#1d2a1a;color:#d5f1c9;border:1px solid #344c2d}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:0 0 18px}.stat{border:1px solid #30291d;border-radius:15px;padding:16px;background:linear-gradient(145deg,#17150f,#101010)}.stat-label{font-size:13px;color:var(--muted)}.stat-value{display:block;font-size:24px;font-weight:800;color:var(--gold);margin-top:5px}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.search{max-width:320px}.table-wrap{width:100%;overflow:auto;border:1px solid #29251f;border-radius:14px}table{width:100%;border-collapse:collapse;min-width:760px;background:#101010}th,td{text-align:right;padding:14px 12px;border-bottom:1px solid #29251f;vertical-align:middle}th{color:var(--muted);font-size:12px;font-weight:700;background:#151515;white-space:nowrap}tbody tr:last-child td{border-bottom:0}tbody tr:hover{background:#ffffff05}td.mac{direction:ltr;text-align:right;font-family:ui-monospace,SFMono-Regular,monospace;color:#f1d38c;white-space:nowrap}.pill{display:inline-flex;align-items:center;gap:7px;padding:5px 10px;border-radius:99px;font-size:12px;font-weight:750;white-space:nowrap}.pill:before{content:"";width:7px;height:7px;border-radius:50%;background:currentColor}.on{background:#24331e;color:var(--green)}.off{background:#35201d;color:#f0a49b}.empty{color:var(--muted);text-align:center;padding:35px 16px}.rowform{margin:0}.rowform button{min-height:40px;padding:8px 13px}.small{font-size:12px;color:var(--muted);margin:15px 0 0}.login{width:min(460px,calc(100% - 36px));margin:12vh auto}.login .field{margin-top:20px}.login button{width:100%}.lockhint{font-size:12px;color:var(--muted);margin-top:15px;text-align:center}.password-card{border-color:#493b24}.password-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.mobile-only{display:none}
@media(max-width:800px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.wrap{width:min(100% - 28px,720px);padding-top:23px}.table-wrap{border:0;overflow:visible}table{min-width:0;background:transparent}thead{display:none}tbody{display:grid;gap:12px}tbody tr{display:block;border:1px solid #30291d;border-radius:15px;padding:8px 13px;background:linear-gradient(145deg,#171717,#101010);box-shadow:0 8px 25px #0004}tbody tr:hover{background:linear-gradient(145deg,#1b1a17,#101010)}tbody td{display:grid;grid-template-columns:minmax(90px,32%) 1fr;align-items:center;gap:12px;padding:10px 2px;border-bottom:1px solid #28251f;text-align:right}tbody td:last-child{border-bottom:0}tbody td:before{content:attr(data-label);color:var(--muted);font-size:12px;font-weight:650}td.mac{justify-content:space-between}.rowform button{width:100%}.toolbar{align-items:stretch}.search{max-width:none}}
@media(max-width:540px){.wrap{width:calc(100% - 22px);padding:15px 0 36px}.topbar{align-items:flex-start;margin-bottom:16px}.identity{gap:10px}.mark{width:42px;height:42px;border-radius:13px}.brand{font-size:11px}.heading{font-size:21px}.sub{font-size:12px}.status-chip{display:none}.top-actions button{min-height:42px;padding:9px 12px}.card{border-radius:16px;padding:16px;margin:12px 0}.section-head{display:block}.section-head h2{font-size:17px}.grid,.password-grid{grid-template-columns:1fr;gap:12px}.field input,.field select{min-height:50px;font-size:16px}.actions{display:grid;grid-template-columns:1fr}.actions button{width:100%}.stats{gap:8px}.stat{padding:12px 10px;border-radius:12px}.stat-label{font-size:11px}.stat-value{font-size:21px}.toolbar{display:block}.search{margin-top:8px}.login{margin:9vh auto}.login button{width:100%}tbody td{grid-template-columns:78px minmax(0,1fr);gap:8px;font-size:14px}td.mac{font-size:13px}}
@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition:none!important}}
</style></head><body>
<?php if (!$authenticated): ?>
<main class="login card"><div class="identity"><div class="mark">N</div><div><div class="brand">NOVO · VPN ACCESS</div><h1 class="heading">لوحة إدارة VPN</h1></div></div><p class="sub">سجّل الدخول لإدارة الأجهزة المسموح لها باستخدام النفق.</p>
<?php if ($error !== ''): ?><div class="message err" role="alert"><?=h($error)?></div><?php endif; ?>
<form method="post" autocomplete="on"><input type="hidden" name="csrf" value="<?=h($token)?>"><input type="hidden" name="action" value="login"><div class="field"><label for="password">كلمة مرور الإدارة</label><input id="password" type="password" name="password" autocomplete="current-password" required autofocus></div><div class="actions"><button type="submit">دخول آمن</button></div></form><div class="lockhint">جلسة مشفّرة عبر HTTPS · صلاحيات المدير فقط</div>
</main>
<?php else: ?>
<main class="wrap">
<header class="topbar"><div class="identity"><div class="mark">N</div><div><div class="brand">NOVO · VPN ACCESS</div><h1 class="heading">مركز التحكم</h1><p class="sub">إدارة صلاحية النفق حسب MAC التطبيق</p></div></div><div class="top-actions"><span class="status-chip">لوحة الإدارة</span><form method="post"><input type="hidden" name="csrf" value="<?=h($token)?>"><input type="hidden" name="action" value="logout"><button class="secondary" type="submit">تسجيل الخروج</button></form></div></header>
<?php if ($error !== ''): ?><div class="message err" role="alert"><?=h($error)?></div><?php endif; ?><?php if ($notice !== ''): ?><div class="message ok" role="status"><?=h($notice)?></div><?php endif; ?>
<section class="stats" aria-label="ملخص الأجهزة"><div class="stat"><span class="stat-label">إجمالي الأجهزة</span><strong class="stat-value"><?=count($devices)?></strong></div><div class="stat"><span class="stat-label">مسموح لها</span><strong class="stat-value"><?=$allowedCount?></strong></div><div class="stat"><span class="stat-label">ممنوعة</span><strong class="stat-value"><?=count($devices)-$allowedCount?></strong></div></section>
<section class="card"><div class="section-head"><div><h2>إضافة أو تعديل جهاز</h2><p class="sub">الأجهزة الجديدة تظهر كممنوعة حتى تفعيلها يدويًا.</p></div></div>
<form method="post"><input type="hidden" name="csrf" value="<?=h($token)?>"><input type="hidden" name="action" value="save"><div class="grid">
<div class="field"><label for="mac">MAC التطبيق</label><input id="mac" name="mac" placeholder="AA:BB:CC:DD:EE:FF" pattern="[A-Fa-f0-9: ._-]{12,20}" autocomplete="off" required></div>
<div class="field"><label for="allowed">صلاحية VPN</label><select id="allowed" name="allowed"><option value="1">مسموح</option><option value="0">ممنوع</option></select></div>
<div class="field"><label for="expires">تاريخ انتهاء الصلاحية</label><input id="expires" type="date" name="expires_at"></div>
<div class="field"><label for="note">ملاحظة إدارية</label><input id="note" name="note" maxlength="180" placeholder="اسم المستخدم أو ملاحظة"></div>
</div><div class="actions"><button type="submit">حفظ الصلاحية</button></div></form></section>
<section class="card"><div class="section-head"><div><h2>الأجهزة المسجلة</h2><p class="sub">ابحث بالـMAC أو الملاحظة، وبدّل السماح بلمسة واحدة.</p></div></div>
<?php if (!$devices): ?><div class="empty">لا توجد أجهزة بعد. افتح التطبيق مرة واحدة لتسجيل MAC هنا.</div><?php else: ?>
<div class="toolbar"><span class="small">آخر الأجهزة ظهورًا في الأعلى</span><input class="search" id="deviceSearch" type="search" placeholder="بحث عن جهاز…" aria-label="بحث عن جهاز"></div>
<div class="table-wrap"><table id="deviceTable"><thead><tr><th>MAC التطبيق</th><th>صلاحية VPN</th><th>آخر ظهور</th><th>الانتهاء</th><th>ملاحظة</th><th>إجراء</th></tr></thead><tbody>
<?php foreach ($devices as $mac => $device): $allowed = vpnIsAllowed((string)$mac); $searchText = strtolower((string)$mac . ' ' . (string)($device['note'] ?? '') . ' ' . ($allowed ? 'مسموح allowed' : 'ممنوع denied')); ?>
<tr data-search="<?=h($searchText)?>"><td class="mac" data-label="MAC التطبيق"><?=h((string)$mac)?></td><td data-label="الصلاحية"><span class="pill <?=$allowed?'on':'off'?>"><?=$allowed?'مسموح':'ممنوع'?></span></td><td data-label="آخر ظهور"><?=h((string)($device['last_seen'] ?? '—'))?></td><td data-label="الانتهاء"><?=h((string)($device['expires_at'] ?? 'بدون انتهاء'))?></td><td data-label="ملاحظة"><?=h((string)($device['note'] ?? '—'))?></td><td data-label="إجراء"><form class="rowform" method="post"><input type="hidden" name="csrf" value="<?=h($token)?>"><input type="hidden" name="action" value="save"><input type="hidden" name="mac" value="<?=h((string)$mac)?>"><input type="hidden" name="allowed" value="<?=$allowed?'0':'1'?>"><input type="hidden" name="expires_at" value="<?=h((string)($device['expires_at'] ?? ''))?>"><input type="hidden" name="note" value="<?=h((string)($device['note'] ?? ''))?>"><button class="<?=$allowed?'danger':'secondary'?>" type="submit"><?=$allowed?'إيقاف VPN':'تفعيل VPN'?></button></form></td></tr>
<?php endforeach; ?></tbody></table></div><div class="empty" id="noSearchResults" hidden>لا توجد نتائج مطابقة.</div><?php endif; ?>
<p class="small">تظهر تغييرات الصلاحية بعد تحديث التطبيق لبيانات المصادقة. النفق النشط لا ينقطع تلقائيًا عند سحب الإذن.</p></section>
<section class="card password-card"><div class="section-head"><div><h2>الأمان وكلمة المرور</h2><p class="sub">غيّر كلمة مرور المدير. يلزم إدخال كلمة المرور الحالية.</p></div></div>
<form method="post" autocomplete="off"><input type="hidden" name="csrf" value="<?=h($token)?>"><input type="hidden" name="action" value="change_password"><div class="password-grid">
<div class="field"><label for="current_password">كلمة المرور الحالية</label><input id="current_password" type="password" name="current_password" autocomplete="current-password" required></div>
<div class="field"><label for="new_password">كلمة المرور الجديدة</label><input id="new_password" type="password" name="new_password" autocomplete="new-password" minlength="12" maxlength="200" required><span class="small">12 خانة على الأقل.</span></div>
<div class="field"><label for="confirm_password">تأكيد كلمة المرور الجديدة</label><input id="confirm_password" type="password" name="confirm_password" autocomplete="new-password" minlength="12" maxlength="200" required></div>
</div><div class="actions"><button class="secondary" type="submit">تغيير كلمة المرور</button></div></form></section>
<footer class="small">NOVO VPN Access · استخدم اللوحة عبر HTTPS واحفظ بيانات الدخول بمكان آمن.</footer>
</main>
<script>
(() => {
  const input = document.getElementById('deviceSearch');
  const table = document.getElementById('deviceTable');
  if (!input || !table) return;
  const rows = Array.from(table.querySelectorAll('tbody tr'));
  const empty = document.getElementById('noSearchResults');
  input.addEventListener('input', () => {
    const query = input.value.trim().toLocaleLowerCase();
    let shown = 0;
    rows.forEach(row => {
      const match = (row.dataset.search || '').toLocaleLowerCase().includes(query);
      row.hidden = !match;
      if (match) shown++;
    });
    if (empty) empty.hidden = shown !== 0;
  });
})();
</script>
<?php endif; ?></body></html>
