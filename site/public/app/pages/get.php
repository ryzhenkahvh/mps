<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/** Counts a download and sends the browser on to the file itself. */
header('Cache-Control: no-store');
$apk = Site::apk();
if (!$apk) {
    header('Location: ' . Site::url('download'), true, 302);
    exit;
}
// the same visitor pressing the button several times within an hour is one download
if (Store::recent('download', Site::visitor()) === 0) {
    Store::event('download', $apk['name']);
}
header('Location: ' . $apk['url'], true, 302);
