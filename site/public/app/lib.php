<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

// The few functions of the mbstring extension the site uses, for a PHP where it is switched off.
if (!function_exists('mb_substr')) {
    function mb_substr(string $text, int $start, ?int $length = null): string
    {
        $chars = preg_split('//u', $text, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        return implode('', array_slice($chars, $start, $length));
    }
}
if (!function_exists('mb_strlen')) {
    function mb_strlen(string $text): int
    {
        return count(preg_split('//u', $text, -1, PREG_SPLIT_NO_EMPTY) ?: []);
    }
}
if (!function_exists('mb_check_encoding')) {
    function mb_check_encoding(string $text, string $encoding = 'UTF-8'): bool
    {
        return preg_match('//u', $text) === 1;
    }
}

/** Escapes text for HTML. */
function e(?string $text): string
{
    return htmlspecialchars((string) $text, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/**
 * Small files the site keeps for itself in app/data (the settings of the database, its own key,
 * answers that wait for the database). Each is a .php file whose first line stops it, so that
 * asking for one by its address gives nothing even where .htaccess is not obeyed.
 */
final class Keep
{
    public const GUARD = "<?php http_response_code(404); exit; ?>\n";

    public static function path(string $name): string
    {
        return MPS_ROOT . '/app/data/' . $name . '.php';
    }

    public static function read(string $name): ?array
    {
        $file = self::path($name);
        if (!is_file($file)) {
            return null;
        }
        $text = (string) @file_get_contents($file);
        $at = strpos($text, "\n");
        $data = $at === false ? null : json_decode(substr($text, $at + 1), true);
        return is_array($data) ? $data : null;
    }

    /**
     * Data as one line of text for such a file. "<" and ">" are written as codes, so that whatever
     * a visitor typed can never look like the start of a script to PHP.
     */
    public static function line(array $data): string
    {
        return (string) json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_INVALID_UTF8_SUBSTITUTE);
    }

    /** Writes the whole file at once: a reader never sees half of it. */
    public static function write(string $name, array $data): bool
    {
        $file = self::path($name);
        $draft = self::path($name . '.' . bin2hex(random_bytes(4)));
        $text = self::GUARD . self::line($data);
        if (@file_put_contents($draft, $text, LOCK_EX) === false) {
            return false;
        }
        @chmod($draft, 0600);
        if (!@rename($draft, $file)) {
            @unlink($draft);
            return false;
        }
        return true;
    }

    public static function forget(string $name): void
    {
        @unlink(self::path($name));
    }
}

/** What every page needs: the language, the texts, addresses of other pages. */
final class Site
{
    public static array $config = [];
    public static string $lang = 'ru';
    public static string $page = 'home';
    private static array $texts = [];

    public static function start(array $config, string $lang, string $page): void
    {
        self::$config = $config;
        self::$lang = $lang;
        self::$page = $page;
        self::$texts = require __DIR__ . '/texts.php';
    }

    /** A text in the language of the page; %s places are filled with the further arguments. */
    public static function t(string $key, string ...$args): string
    {
        $text = self::$texts[self::$lang][$key] ?? self::$texts['ru'][$key] ?? $key;
        return $args ? vsprintf($text, $args) : $text;
    }

    /** A list of texts (features, questions of the FAQ...). */
    public static function list(string $key): array
    {
        return self::$texts[self::$lang][$key] ?? self::$texts['ru'][$key] ?? [];
    }

    /** Address of a page in the current (or the given) language: url('survey') -> /survey/ or /en/survey/. */
    public static function url(string $page = 'home', ?string $lang = null): string
    {
        $prefix = ($lang ?? self::$lang) === 'en' ? '/en' : '';
        return $prefix . ($page === 'home' ? '/' : '/' . $page . '/');
    }

    public static function asset(string $file): string
    {
        $path = MPS_ROOT . '/assets/' . $file;
        // the time of the last change makes browsers fetch a file again after an update of the site
        return '/assets/' . $file . (is_file($path) ? '?v=' . filemtime($path) : '');
    }

    /** The build in /download that visitors get (the one with the highest version), or null while there is none. */
    public static function apk(): ?array
    {
        $files = glob(MPS_ROOT . '/download/*.apk') ?: [];
        if (!$files) {
            return null;
        }
        $number = static fn (string $file): string => preg_match('/(\d+(?:\.\d+)+)/', basename($file), $m) ? $m[1] : '';
        // the highest version wins; of two files with the same version, the newer one
        usort($files, static fn ($a, $b) => version_compare($number($b) ?: '0', $number($a) ?: '0') ?: (filemtime($b) <=> filemtime($a)));
        $file = array_shift($files);
        // An update of the site is unpacked over the old files, so the build of the previous version is still
        // lying next to the new one. It goes: it only takes room, and an old link to it would keep handing out
        // an outdated app (404.php sends such links to the current build).
        foreach ($files as $old) {
            @unlink($old);
        }
        $name = basename($file);
        $version = $number($file);
        // earlier versions of the site remembered a checksum of the build here; it is not shown any more
        if (is_file(Keep::path('apk'))) {
            @unlink(Keep::path('apk'));
        }
        return [
            'name' => $name,
            'url' => '/download/' . rawurlencode($name),
            'version' => $version,
            'bytes' => filesize($file),
            'megabytes' => number_format(filesize($file) / 1048576, 0, '.', ''),
            'date' => date('Y-m-d', filemtime($file)),
        ];
    }

    /**
     * The key of this copy of the site: it signs the forms and makes the anonymous marks of visitors.
     * Nobody has to invent it: the site makes one on the first request and keeps it in app/data.
     */
    public static function secret(): string
    {
        static $secret = null;
        if ($secret !== null) {
            return $secret;
        }
        $secret = (string) (self::$config['secret'] ?? '');
        if ($secret === '') {
            $kept = Keep::read('key');
            $secret = is_string($kept['secret'] ?? null) ? $kept['secret'] : '';
        }
        if ($secret === '') {
            $secret = bin2hex(random_bytes(32));
            if (!Keep::write('key', ['secret' => $secret])) {
                // nowhere to keep it: a value that at least stays the same for this copy of the site
                $secret = hash('sha256', 'mps|' . __FILE__ . '|' . @fileinode(__FILE__) . '|' . @filectime(__FILE__));
            }
        }
        return $secret;
    }

    /** A value signed with the key of the site, so that it cannot be made up by a visitor. */
    public static function sign(string $value): string
    {
        return hash_hmac('sha256', $value, self::secret());
    }

    /** The address of the visitor; behind the front server of a hosting it arrives in a header. */
    private static function address(): string
    {
        $ip = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
        // the headers are believed only when the request came from the hosting itself (its front server)
        $own = $ip === '' || $ip === (string) ($_SERVER['SERVER_ADDR'] ?? '-')
            || filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE) === false;
        if ($own) {
            $list = explode(',', (string) ($_SERVER['HTTP_X_FORWARDED_FOR'] ?? ''));
            foreach ([(string) ($_SERVER['HTTP_X_REAL_IP'] ?? ''), (string) end($list)] as $candidate) {
                if (filter_var(trim($candidate), FILTER_VALIDATE_IP)) {
                    return trim($candidate);
                }
            }
        }
        return $ip;
    }

    /** Who is asking, without keeping the address itself: the same visitor gives the same value for a day. */
    public static function visitor(): string
    {
        return self::sign(self::address() . '|' . gmdate('Y-m-d'));
    }

    /** A screenshot of the app in the language of the page. */
    public static function shot(string $name): string
    {
        return self::asset('shots/' . self::$lang . '/' . $name . '.png');
    }
}

function t(string $key, string ...$args): string
{
    return Site::t($key, ...$args);
}

/** Cuts a submitted value to a sane length and removes control characters. */
function clean(mixed $value, int $max = 200): string
{
    if (!is_string($value)) {
        return '';
    }
    // text that is not valid UTF-8 is dropped rather than stored broken
    if (!mb_check_encoding($value, 'UTF-8')) {
        return '';
    }
    $value = str_replace(["\r\n", "\r"], "\n", $value);
    $value = preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/u', '', $value) ?? '';
    return trim(mb_substr($value, 0, $max));
}

/** The page frame: head, header, the content, footer. */
function layout(string $title, string $description, callable $content, array $options = []): void
{
    $lang = Site::$lang;
    $other = $lang === 'ru' ? 'en' : 'ru';
    $page = Site::$page;
    $canonical = rtrim((string) Site::$config['site_url'], '/') . Site::url($page);
    $noindex = !empty($options['noindex']);
    // the page moves itself from plain http to this address (see assets/site.js)
    $address = parse_url((string) Site::$config['site_url']);
    $home = ($address['scheme'] ?? '') === 'https' && preg_match('/^[a-z0-9.-]+$/', (string) ($address['host'] ?? '')) ? 'https://' . $address['host'] : '';
    ?>
<!doctype html>
<html lang="<?= e($lang) ?>"<?= $home !== '' ? ' data-home="' . e($home) . '"' : '' ?>>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= e($title) ?></title>
<meta name="description" content="<?= e($description) ?>">
<?php if ($noindex): ?>
<meta name="robots" content="noindex, nofollow">
<?php else: ?>
<link rel="canonical" href="<?= e($canonical) ?>">
<link rel="alternate" hreflang="ru" href="<?= e(rtrim((string) Site::$config['site_url'], '/') . Site::url($page, 'ru')) ?>">
<link rel="alternate" hreflang="en" href="<?= e(rtrim((string) Site::$config['site_url'], '/') . Site::url($page, 'en')) ?>">
<meta property="og:type" content="website">
<meta property="og:title" content="<?= e($title) ?>">
<meta property="og:description" content="<?= e($description) ?>">
<meta property="og:url" content="<?= e($canonical) ?>">
<meta property="og:image" content="<?= e(rtrim((string) Site::$config['site_url'], '/') . '/assets/og.png') ?>">
<meta name="twitter:card" content="summary_large_image">
<?php endif; ?>
<meta name="theme-color" content="#ff3e8a">
<link rel="icon" href="/assets/favicon.png" type="image/png">
<link rel="apple-touch-icon" href="/assets/icon-192.png">
<link rel="stylesheet" href="<?= e(Site::asset('site.css')) ?>">
<script src="<?= e(Site::asset('site.js')) ?>" defer></script>
</head>
<body class="page-<?= e($page) ?>">
<a class="skip" href="#main"><?= e(t('skip')) ?></a>
<header class="top">
  <div class="wrap top-row">
    <a class="brand" href="<?= e(Site::url('home')) ?>" aria-label="MPS"><img src="/assets/logo.svg" alt="MPS" width="94" height="32"></a>
    <button class="menu-button" type="button" aria-expanded="false" aria-controls="nav" aria-label="<?= e(t('menu')) ?>"><span></span><span></span><span></span></button>
    <nav id="nav" class="nav" aria-label="<?= e(t('menu')) ?>">
      <a href="<?= e(Site::url('home')) ?>#features"><?= e(t('nav_features')) ?></a>
      <a href="<?= e(Site::url('home')) ?>#community"><?= e(t('nav_community')) ?></a>
      <a href="<?= e(Site::url('download')) ?>"<?= $page === 'download' ? ' aria-current="page"' : '' ?>><?= e(t('nav_download')) ?></a>
      <a href="<?= e(Site::url('survey')) ?>"<?= $page === 'survey' ? ' aria-current="page"' : '' ?>><?= e(t('nav_survey')) ?></a>
      <a class="lang" href="<?= e(Site::url(in_array($page, ['home', 'download', 'survey', 'feedback', 'privacy', 'terms'], true) ? $page : 'home', $other)) ?>" hreflang="<?= e($other) ?>" lang="<?= e($other) ?>"><?= $other === 'en' ? 'English' : 'Русский' ?></a>
    </nav>
  </div>
</header>
<main id="main">
<?php $content(); ?>
</main>
<footer class="foot">
  <div class="wrap foot-row">
    <div>
      <img src="/assets/logo.svg" alt="" width="70" height="24">
      <p><?= e(t('foot_line')) ?></p>
    </div>
    <nav aria-label="<?= e(t('foot_nav')) ?>">
      <a href="<?= e(Site::url('feedback')) ?>"><?= e(t('nav_feedback')) ?></a>
      <a href="<?= e(Site::url('privacy')) ?>"><?= e(t('nav_privacy')) ?></a>
      <a href="<?= e(Site::url('terms')) ?>"><?= e(t('nav_terms')) ?></a>
      <?php if ((string) (Site::$config['telegram'] ?? '') !== ''): ?>
      <a href="https://t.me/<?= e((string) Site::$config['telegram']) ?>" rel="noopener">Telegram</a>
      <?php endif; ?>
      <a href="mailto:<?= e((string) Site::$config['support_email']) ?>"><?= e((string) Site::$config['support_email']) ?></a>
    </nav>
  </div>
</footer>
</body>
</html>
<?php
}

/** A hidden pair of fields that tells a form filled by a person from one filled by a script. */
function form_guard(): string
{
    $time = (string) time();
    return '<input type="hidden" name="started" value="' . e($time . '.' . Site::sign('form' . $time)) . '">'
        . '<div class="hp" aria-hidden="true"><label>Website <input type="text" name="website" tabindex="-1" autocomplete="off"></label></div>';
}

/**
 * Null when the form may be accepted; otherwise why not:
 * 'bot' (the field people do not see is filled), 'stale' (the stamp of the form is not ours:
 * the page was open while the site was being set up again), 'fast' (sent within three seconds).
 */
function form_guard_problem(array $post): ?string
{
    if (clean($post['website'] ?? '') !== '') {
        return 'bot';
    }
    $parts = explode('.', clean($post['started'] ?? '', 120), 2);
    if (count($parts) !== 2 || preg_match('/^\d{1,12}$/', $parts[0]) !== 1 || !hash_equals(Site::sign('form' . $parts[0]), $parts[1])) {
        return 'stale';
    }
    return time() - (int) $parts[0] < 3 ? 'fast' : null;
}
