<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/**
 * Where answers are kept: the MySQL database of the hosting. While it cannot be reached
 * (the site is not set up yet, the password was changed, the database is down) nothing is lost:
 * answers go to a file in app/data instead and are moved into the database the next time it works.
 */
final class Store
{
    private static ?PDO $pdo = null;
    public static string $problem = '';

    /** True when the site knows which database to use (whether or not it answers). */
    public static function configured(): bool
    {
        $c = Site::$config['db'] ?? null;
        return is_array($c) && (string) ($c['name'] ?? '') !== '';
    }

    public static function db(): ?PDO
    {
        if (self::$pdo !== null || self::$problem !== '') {
            return self::$pdo;
        }
        if (!self::configured()) {
            self::$problem = 'not-set';
            return null;
        }
        $c = Site::$config['db'];
        self::open($c);
        return self::$pdo;
    }

    /**
     * Connects with the given settings and makes them the ones in use. On success $c['host']
     * holds the address that answered. False leaves the reason in $problem.
     */
    public static function open(array &$c): bool
    {
        self::$pdo = null;
        self::$problem = '';
        try {
            $pdo = self::connect($c);
            self::tables($pdo);
            self::$pdo = $pdo;
            self::drain();
            return true;
        } catch (Throwable $e) {
            self::$problem = $e->getMessage() !== '' ? $e->getMessage() : 'unknown';
            return false;
        }
    }

    private static function connect(array &$c): PDO
    {
        if (!extension_loaded('pdo_mysql')) {
            throw new RuntimeException('no-driver');
        }
        $name = (string) ($c['name'] ?? '');
        // the name goes into the connection string: nothing but a plain name may get there
        if (preg_match('/^[A-Za-z0-9_$.-]{1,64}$/', $name) !== 1) {
            throw new RuntimeException('[1049] bad database name');
        }
        $host = (string) ($c['host'] ?? 'localhost');
        if (preg_match('/^[A-Za-z0-9_.:-]{1,255}$/', $host) !== 1) {
            throw new RuntimeException('[2005] bad host name');
        }
        // "localhost" means a socket file whose place PHP may not know; the same server also answers on 127.0.0.1
        $hosts = $host === 'localhost' ? ['localhost', '127.0.0.1'] : [$host];
        $last = null;
        foreach ($hosts as $h) {
            try {
                $pdo = new PDO('mysql:host=' . $h . ';port=' . (int) ($c['port'] ?? 3306) . ';dbname=' . $name . ';charset=utf8mb4', (string) ($c['user'] ?? ''), (string) ($c['password'] ?? ''), [
                    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                    PDO::ATTR_EMULATE_PREPARES => false,
                    PDO::ATTR_TIMEOUT => 5,
                ]);
                $c['host'] = $h;
                return $pdo;
            } catch (PDOException $e) {
                $last = $e;
                // a refused name or password will be refused on the other address too
                if (preg_match('/\[(1044|1045|1049|1698)\]/', $e->getMessage()) === 1) {
                    break;
                }
            }
        }
        throw $last ?? new RuntimeException('unknown');
    }

    private static function tables(PDO $pdo): void
    {
        $pdo->exec("CREATE TABLE IF NOT EXISTS mps_responses (
            id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            kind VARCHAR(16) NOT NULL,
            created_at DATETIME NOT NULL,
            lang VARCHAR(5) NOT NULL DEFAULT '',
            source VARCHAR(32) NOT NULL DEFAULT '',
            app_version VARCHAR(32) NOT NULL DEFAULT '',
            device VARCHAR(120) NOT NULL DEFAULT '',
            answers MEDIUMTEXT NOT NULL,
            contact VARCHAR(190) NOT NULL DEFAULT '',
            visitor CHAR(64) NOT NULL DEFAULT '',
            KEY by_kind (kind, created_at),
            KEY by_visitor (visitor, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mps_events (
            id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            kind VARCHAR(16) NOT NULL,
            created_at DATETIME NOT NULL,
            detail VARCHAR(190) NOT NULL DEFAULT '',
            visitor CHAR(64) NOT NULL DEFAULT '',
            KEY by_kind (kind, created_at),
            KEY by_visitor (visitor, kind, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mps_settings (
            name VARCHAR(64) NOT NULL PRIMARY KEY,
            value TEXT NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    /**
     * Why there is no database, as one word for the pages to explain in plain language:
     * '' (it works), 'not-set', 'driver', 'denied', 'no-database', 'down'.
     */
    public static function reason(): string
    {
        $p = self::$problem;
        if (self::$pdo !== null || $p === '') {
            return '';
        }
        if ($p === 'not-set') {
            return 'not-set';
        }
        if ($p === 'no-driver' || stripos($p, 'could not find driver') !== false) {
            return 'driver';
        }
        if (preg_match('/\[(1045|1698)\]/', $p) === 1) {
            return 'denied';
        }
        if (preg_match('/\[(1044|1049)\]/', $p) === 1) {
            return 'no-database';
        }
        return 'down';
    }

    private static function spare(): string
    {
        return Keep::path('waiting');
    }

    private static function blank(): array
    {
        return ['kind' => '', 'created_at' => gmdate('Y-m-d H:i:s'), 'lang' => '', 'source' => '', 'app_version' => '', 'device' => '', 'answers' => '{}', 'contact' => '', 'visitor' => ''];
    }

    /**
     * One request at a time works with the spare file: an answer being written and the move into
     * the database never cross. $wait false gives up at once when somebody else is at it.
     */
    private static function alone(callable $do, bool $wait = true): mixed
    {
        $lock = @fopen(Keep::path('lock'), 'c');
        if ($lock === false) {
            return $do();
        }
        if (!flock($lock, $wait ? LOCK_EX : LOCK_EX | LOCK_NB)) {
            fclose($lock);
            return $wait ? $do() : null;
        }
        if ((fstat($lock)['size'] ?? 1) === 0) {
            // like every file here, this one says nothing when asked for by its address
            fwrite($lock, Keep::GUARD);
        }
        try {
            return $do();
        } finally {
            flock($lock, LOCK_UN);
            fclose($lock);
        }
    }

    /** Saves an answer. True when it is safe somewhere (database or the spare file). */
    public static function add(array $row): bool
    {
        $row += self::blank();
        $pdo = self::db();
        if ($pdo) {
            try {
                self::insert($pdo, $row);
                return true;
            } catch (Throwable $e) {
                self::$problem = $e->getMessage();
            }
        }
        return (bool) self::alone(static function () use ($row): bool {
            $file = self::spare();
            $fresh = !is_file($file);
            if (!$fresh && filesize($file) > 5 * 1048576) {
                return false;
            }
            $line = ($fresh ? Keep::GUARD : '') . Keep::line($row) . "\n";
            if (@file_put_contents($file, $line, FILE_APPEND) === false) {
                return false;
            }
            if ($fresh) {
                @chmod($file, 0600);
            }
            return true;
        });
    }

    private static function insert(PDO $pdo, array $row): void
    {
        $st = $pdo->prepare('INSERT INTO mps_responses (kind, created_at, lang, source, app_version, device, answers, contact, visitor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $st->execute([$row['kind'], $row['created_at'], $row['lang'], $row['source'], $row['app_version'], $row['device'], $row['answers'], $row['contact'], $row['visitor']]);
    }

    /** Answers that wait in the spare file (the database was unreachable), still as rows. */
    public static function waiting(): array
    {
        $file = self::spare();
        if (!is_file($file)) {
            return [];
        }
        $rows = [];
        foreach (array_slice(@file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [], 1) as $line) {
            $row = json_decode($line, true);
            if (is_array($row)) {
                $rows[] = $row;
            }
        }
        return $rows;
    }

    /** Moves what waited in the spare file into the database: all of it or, on any trouble, nothing. */
    private static function drain(): void
    {
        $pdo = self::$pdo;
        if (!$pdo || !is_file(self::spare())) {
            return;
        }
        self::alone(static function () use ($pdo): void {
            $rows = self::waiting();
            try {
                $pdo->beginTransaction();
                foreach ($rows as $row) {
                    $row = array_intersect_key($row, self::blank()) + self::blank();
                    self::insert($pdo, array_map(static fn ($v) => is_scalar($v) ? (string) $v : '', $row));
                }
                $pdo->commit();
                @unlink(self::spare());
            } catch (Throwable $e) {
                if ($pdo->inTransaction()) {
                    $pdo->rollBack();
                }
            }
        }, false);
    }

    /** How many times this visitor did [kind] within the last hour (spam and password guessing). */
    public static function recent(string $kind, string $visitor): int
    {
        $pdo = self::db();
        if (!$pdo) {
            return 0;
        }
        $table = in_array($kind, ['survey', 'feedback'], true) ? 'mps_responses' : 'mps_events';
        try {
            $st = $pdo->prepare("SELECT COUNT(*) FROM $table WHERE kind = ? AND visitor = ? AND created_at > (UTC_TIMESTAMP() - INTERVAL 1 HOUR)");
            $st->execute([$kind, $visitor]);
            return (int) $st->fetchColumn();
        } catch (Throwable $e) {
            return 0;
        }
    }

    public static function event(string $kind, string $detail = ''): void
    {
        $pdo = self::db();
        if (!$pdo) {
            return;
        }
        try {
            $pdo->prepare('INSERT INTO mps_events (kind, created_at, detail, visitor) VALUES (?, UTC_TIMESTAMP(), ?, ?)')->execute([$kind, mb_substr($detail, 0, 190), Site::visitor()]);
        } catch (Throwable $e) {
        }
    }

    public static function setting(string $name): ?string
    {
        $pdo = self::db();
        if (!$pdo) {
            return null;
        }
        $st = $pdo->prepare('SELECT value FROM mps_settings WHERE name = ?');
        $st->execute([$name]);
        $value = $st->fetchColumn();
        return $value === false ? null : (string) $value;
    }

    public static function set(string $name, string $value): void
    {
        $pdo = self::db();
        if ($pdo) {
            $pdo->prepare('INSERT INTO mps_settings (name, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)')->execute([$name, $value]);
        }
    }
}
