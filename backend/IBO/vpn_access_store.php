<?php
declare(strict_types=1);

/** Shared MAC-based VPN entitlement store for the auth API and admin panel. */
function vpnAccessConfig(): array
{
    static $config = null;
    if ($config !== null) return $config;

    $configFile = __DIR__ . '/vpn_access_config.php';
    $config = is_file($configFile) ? require $configFile : [];
    if (!is_array($config)) $config = [];
    return $config;
}

function vpnAccessStorePath(): string
{
    $config = vpnAccessConfig();
    $environmentPath = getenv('NOVO_VPN_ACCESS_FILE');
    $path = is_string($environmentPath) && $environmentPath !== ''
        ? $environmentPath
        : ($config['store_file'] ?? (__DIR__ . '/.private/vpn_access.json'));
    return (string)$path;
}

function vpnNormalizeMac(string $mac): ?string
{
    $hex = strtoupper((string)preg_replace('/[:.\-\s_]/', '', trim($mac)));
    if (!preg_match('/^[A-F0-9]{12}$/', $hex)) return null;
    return implode(':', str_split($hex, 2));
}

function vpnReadStoreLocked(callable $callback, bool $write)
{
    $path = vpnAccessStorePath();
    $directory = dirname($path);
    if (!is_dir($directory) && !@mkdir($directory, 0700, true) && !is_dir($directory)) {
        throw new RuntimeException('VPN access storage directory is not writable');
    }

    $lock = @fopen($path . '.lock', 'c');
    if ($lock === false) throw new RuntimeException('Cannot open VPN access lock file');
    if (!flock($lock, $write ? LOCK_EX : LOCK_SH)) {
        fclose($lock);
        throw new RuntimeException('Cannot lock VPN access storage');
    }

    try {
        $store = ['devices' => []];
        if (is_file($path)) {
            $raw = file_get_contents($path);
            $decoded = is_string($raw) ? json_decode($raw, true) : null;
            if (!is_array($decoded)) throw new RuntimeException('VPN access data is invalid JSON');
            $store = $decoded;
        }
        if (!isset($store['devices']) || !is_array($store['devices'])) $store['devices'] = [];

        $result = $callback($store);
        if ($write) {
            $json = json_encode($store, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
            if (!is_string($json)) throw new RuntimeException('Could not encode VPN access data');
            $temporary = $path . '.tmp.' . bin2hex(random_bytes(6));
            if (file_put_contents($temporary, $json . "\n", LOCK_EX) === false) {
                throw new RuntimeException('Could not write VPN access data');
            }
            @chmod($temporary, 0600);
            if (!@rename($temporary, $path)) {
                @unlink($temporary);
                throw new RuntimeException('Could not replace VPN access data');
            }
            @chmod($path, 0600);
        }
        return $result;
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
}

function vpnTouchDevice(string $mac): void
{
    $normalized = vpnNormalizeMac($mac);
    if ($normalized === null) return;
    vpnReadStoreLocked(static function (array &$store) use ($normalized): void {
        $now = gmdate('c');
        if (!isset($store['devices'][$normalized]) || !is_array($store['devices'][$normalized])) {
            $store['devices'][$normalized] = [
                'allowed' => false,
                'expires_at' => null,
                'note' => '',
                'created_at' => $now,
                'updated_at' => $now,
                'last_seen' => $now,
            ];
        } else {
            $store['devices'][$normalized]['last_seen'] = $now;
        }
    }, true);
}

function vpnIsAllowed(string $mac): bool
{
    $normalized = vpnNormalizeMac($mac);
    if ($normalized === null) return false;
    return vpnReadStoreLocked(static function (array &$store) use ($normalized): bool {
        $record = $store['devices'][$normalized] ?? null;
        if (!is_array($record) || ($record['allowed'] ?? false) !== true) return false;
        $expires = $record['expires_at'] ?? null;
        if (!is_string($expires) || $expires === '') return true;
        $expiry = DateTimeImmutable::createFromFormat('!Y-m-d', $expires, new DateTimeZone('UTC'));
        return $expiry !== false && $expiry >= new DateTimeImmutable('today', new DateTimeZone('UTC'));
    }, false);
}

function vpnSaveDevice(string $mac, bool $allowed, ?string $expiresAt, string $note): string
{
    $normalized = vpnNormalizeMac($mac);
    if ($normalized === null) throw new InvalidArgumentException('Enter a valid 12-digit MAC address');
    if ($expiresAt !== null && $expiresAt !== '') {
        $date = DateTimeImmutable::createFromFormat('!Y-m-d', $expiresAt, new DateTimeZone('UTC'));
        if ($date === false || $date->format('Y-m-d') !== $expiresAt) {
            throw new InvalidArgumentException('Expiry must use YYYY-MM-DD');
        }
    } else {
        $expiresAt = null;
    }

    vpnReadStoreLocked(static function (array &$store) use ($normalized, $allowed, $expiresAt, $note): void {
        $old = $store['devices'][$normalized] ?? [];
        $now = gmdate('c');
        $store['devices'][$normalized] = [
            'allowed' => $allowed,
            'expires_at' => $expiresAt,
            'note' => trim(substr($note, 0, 180)),
            'created_at' => is_array($old) && !empty($old['created_at']) ? $old['created_at'] : $now,
            'updated_at' => $now,
            'last_seen' => is_array($old) ? ($old['last_seen'] ?? null) : null,
        ];
    }, true);
    return $normalized;
}

function vpnListDevices(): array
{
    return vpnReadStoreLocked(static function (array &$store): array {
        $devices = $store['devices'];
        uksort($devices, static function (string $a, string $b) use ($devices): int {
            return strcmp((string)($devices[$b]['last_seen'] ?? ''), (string)($devices[$a]['last_seen'] ?? ''));
        });
        return $devices;
    }, false);
}

function vpnAdminPasswordHash(): string
{
    $environmentHash = getenv('NOVO_VPN_ADMIN_PASSWORD_HASH');
    if (is_string($environmentHash) && $environmentHash !== '') return $environmentHash;

    $config = vpnAccessConfig();
    $passwordFile = (string)($config['admin_password_file'] ?? (__DIR__ . '/.private/admin_password.json'));
    if (is_file($passwordFile)) {
        $stored = json_decode((string)@file_get_contents($passwordFile), true);
        if (is_array($stored) && isset($stored['password_hash']) && is_string($stored['password_hash'])) {
            return $stored['password_hash'];
        }
    }

    return (string)($config['admin_password_hash'] ?? '');
}

function vpnSetAdminPasswordHash(string $passwordHash): void
{
    if ($passwordHash === '' || strlen($passwordHash) > 512) {
        throw new InvalidArgumentException('Invalid password hash');
    }

    $config = vpnAccessConfig();
    $path = (string)($config['admin_password_file'] ?? (__DIR__ . '/.private/admin_password.json'));
    $directory = dirname($path);
    if (!is_dir($directory) && !@mkdir($directory, 0700, true) && !is_dir($directory)) {
        throw new RuntimeException('Cannot create private admin-password storage');
    }

    $json = json_encode([
        'password_hash' => $passwordHash,
        'updated_at' => gmdate('c'),
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    if (!is_string($json)) throw new RuntimeException('Could not encode admin-password data');

    $temporary = $path . '.tmp.' . bin2hex(random_bytes(6));
    if (file_put_contents($temporary, $json . "\n", LOCK_EX) === false) {
        throw new RuntimeException('Could not write new admin password');
    }
    @chmod($temporary, 0600);
    if (!@rename($temporary, $path)) {
        @unlink($temporary);
        throw new RuntimeException('Could not activate new admin password');
    }
    @chmod($path, 0600);
}
