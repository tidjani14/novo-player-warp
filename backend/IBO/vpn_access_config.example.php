<?php
// Copy this file to vpn_access_config.php and set a real password hash.
// Generate one on the server with:
//   php -r 'echo password_hash("YOUR-STRONG-PASSWORD", PASSWORD_DEFAULT), PHP_EOL;'
return [
    'admin_password_hash' => 'REPLACE_WITH_PASSWORD_HASH',
    // Prefer a path outside the public web root. NOVO_VPN_ACCESS_FILE can override it.
    'store_file' => __DIR__ . '/.private/vpn_access.json',
];
