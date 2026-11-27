<?php
$functionsFile = "../includes/functions.php";
ini_set("display_errors", 0);
include($functionsFile);
require_once __DIR__ . '/vpn_access_store.php';
$dnsInfo = loadAllDNS(true);
$iboInfo = getDbEntry("ibo_settings");
$allowedCharacters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";

function getEncryptKeyPosition($str)
{
    global $allowedCharacters;
    return strpos($allowedCharacters, $str);
}

function getEncryptPositionString($i)
{
    global $allowedCharacters;
    return strval($allowedCharacters[$i]);
}

function getDecodedString($str, $type = 1)
{
    $endKey = substr($str, strlen($str) - 2, 2);
    if ($type == 1) {
        $encryptKeyPosition = getEncryptKeyPosition($endKey[0]);
        $encryptKeyPosition2 = getEncryptKeyPosition($endKey[1]);
    } else {
        $encryptKeyPosition = getEncryptKeyPosition($endKey[1]);
        $encryptKeyPosition2 = getEncryptKeyPosition($endKey[0]);
    }
    $substring = substr($str, 0, strlen($str) - 2);
    return base64_decode(trim(substr($substring, 0, $encryptKeyPosition) . substr($substring, ($encryptKeyPosition + $encryptKeyPosition2), strlen($substring))));
}

function getEncodedString($str)
{
    $enc = base64_encode($str);
    $position1 = rand(0, 61);
    $position2 = rand(0, 61);
    $key1 = getEncryptPositionString($position2);
    $key2 = getEncryptPositionString($position1);
    $firstPart = substr($enc, 0, $position1);
    $secondPart = substr($enc, $position1, strlen($enc));
    $padding = "";
    for ($i = 0; $i < $position2; $i++) {
        $paddingRand = rand(0, 61);
        $padding .= getEncryptPositionString($paddingRand);
    }
    return $firstPart . $padding . $secondPart . $key2 . $key1;
}

function getResponseBasic()
{
    $response = [
        "android_version_code" => "1.0.0",
        "apk_url" => "http://google.com",
        "device_key" => "399513",
        "expire_date" => "2026-03-26",
        "is_google_paid" => false,
        "is_trial" => 0,
        "languages" => json_decode(file_get_contents("language.json"), true),
        "mac_registered" => true,
        "vpn_allowed" => false,
        "themes" => [],
        "trial_days" => 7,
        "plan_id" => "36269518",
        "mac_address" => "",
        "pin" => "0000",
        "price" => "7.99",
        "app_version" => "3.1",
        "apk_link" => "http://google.com",
        "urls" => [],
        "note_title" => "",
        "note_content" => ""
    ];
    return $response;
}

$jsonIn = file_get_contents("php://input");
$response = json_decode($jsonIn, true);
$decoded = getDecodedString($response['data']);

if ($_GET['path'] == "auth") {
    $authData = json_decode($decoded, true);
    $macAddress = base64_decode($authData['app_device_id']);
    $macAddress = substr($macAddress, 0, 12);
    $formattedMac = preg_replace('/..(?!$)/', '$0:', $macAddress);
    // Register new app MACs as denied by default; only the private admin panel
    // can grant this separate VPN entitlement. Playlist trial rules stay intact.
    vpnTouchDevice($formattedMac);

    // إنشاء الاستجابة الأساسية
    $basicResponse = getResponseBasic();
    $basicResponse['vpn_allowed'] = vpnIsAllowed($formattedMac);
    $basicResponse['urls'] = [];
    $accounts = getUsersForMac($formattedMac);

    // التحقق من عنوان MAC في جدول القائمة البيضاء
    $whitelistEntry = getWhitelistEntry($formattedMac);
    $isValidMac = true;

    if (!$whitelistEntry) {
        // إذا كان عنوان MAC غير موجود، أضف إلى قائمة البيضاء مع صلاحية 3 أيام
        $isValidMac = false;
        $expirationDate = date('Y-m-d', strtotime('+3 days')); // تاريخ الصلاحية بعد 3 أيام
        addWhitelistEntry($formattedMac, $expirationDate); // إضافة عنوان MAC إلى قائمة البيضاء
        $whitelistEntry = ['expiration_date' => $expirationDate]; // تحديث المتغير
        $basicResponse['is_trial'] = 1; // تعيين حالة التجربة
        $basicResponse['trial_days'] = 3; // تعيين عدد أيام التجربة
    } elseif (strtotime($whitelistEntry['expiration_date']) < strtotime(date('Y-m-d'))) {
        // إذا كان عنوان MAC موجودًا لكن تاريخ الصلاحية منتهي
        $isValidMac = false;
        $basicResponse['is_trial'] = 0; // لا تجربة
        $basicResponse['trial_days'] = 0; // لا أيام تجربة
    } else {
        // إذا كان عنوان MAC موجودًا وتاريخ الصلاحية ساري
        $basicResponse['is_trial'] = 1; // تعيين حالة التجربة
        $basicResponse['trial_days'] = round((strtotime($whitelistEntry['expiration_date']) - strtotime(date('Y-m-d'))) / (60 * 60 * 24)); // حساب الأيام المتبقية
    }

    // إذا كان عنوان MAC صالحًا أو تم تسجيله للتو مع صلاحية سارية، أضف روابط DNS
    if ($isValidMac || (!empty($whitelistEntry['expiration_date']) && strtotime($whitelistEntry['expiration_date']) >= strtotime(date('Y-m-d')))) {
        foreach ($dnsInfo as $thisDns) {
            if (isset($accounts[$thisDns['id']])) {
                $username = $accounts[$thisDns['id']]['username'];
                $password = $accounts[$thisDns['id']]['password'];
                if (!empty($username) && !empty($password)) {
                    $portal = [
                        "is_protected" => 0,
                        "id" => $thisDns['id'],
                        "url" => $thisDns['portal'] . "/get.php?username=" . $username . "&password=" . $password . "&type=m3u_plus&output=ts",
                        "name" => $thisDns['name'],
                        "type" => "xc",
                        "created_at" => "2023-03-26 16:42:48",
                        "updated_at" => "2023-03-26 16:42:48"
                    ];
                    $basicResponse['urls'][] = $portal;
                }
            }
        }
    }

    $basicResponse['note_title'] = $iboInfo['welcome_header'];
    $basicResponse['note_content'] = $iboInfo['welcome_text'];
    $basicResponse['mac_address'] = $formattedMac;
    $basicResponse['expire_date'] = $whitelistEntry['expiration_date'] ?? date('Y-m-d', strtotime('+3 days')); // تحديث تاريخ الصلاحية

    $returnable = ['data' => getEncodedString(json_encode($basicResponse))];
    echo json_encode($returnable);
} elseif ($_GET['path'] == "update") {
    $playlistData = json_decode($decoded, true);
    $macAddress = $playlistData['mac_address'];
    $newURL = $playlistData['playlist_url'];
    $dnsId = $playlistData['playlist_id'];
    $playlistName = $playlistData['playlist_name'];
    $urlParts = parse_url($newURL, PHP_URL_QUERY);
    parse_str($urlParts, $parsed);
    $newUsername = $parsed['username'];
    $newPassword = $parsed['password'];
    addOrUpdateAccount($dnsId, $macAddress, $newUsername, $newPassword);

    $response = [
        "success" => 1,
        "id" => $dnsId,
        "name" => $playlistName,
        "url" => $newURL
    ];
    echo json_encode($response);
}
?>
