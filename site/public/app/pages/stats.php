<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

require_once __DIR__ . '/../qr.php';
require_once __DIR__ . '/../charts.php';

$questions = require __DIR__ . '/../survey.php';
$lang = Site::$lang;
$segment = clean($_GET['segment'] ?? 'all', 20);
$rows = [];
$downloads = null;

$pdo = Store::db();
if ($pdo) {
    $rows = $pdo->query("SELECT answers, lang, created_at FROM mps_responses WHERE kind = 'survey' ORDER BY id")->fetchAll();
    $downloads = (int) $pdo->query("SELECT COUNT(*) FROM mps_events WHERE kind = 'download'")->fetchColumn();
} else {
    foreach (Store::waiting() as $w) {
        if (($w['kind'] ?? '') === 'survey' && is_string($w['answers'] ?? null)) {
            $rows[] = [
                'answers' => $w['answers'],
                'lang' => (string) ($w['lang'] ?? 'ru'),
                'created_at' => (string) ($w['at'] ?? ''),
            ];
        }
    }
}

$publicUrl = rtrim((string) Site::$config['site_url'], '/') . Site::url('stats', $lang);

layout(t('stats_title'), t('stats_description'), static function () use ($rows, $questions, $lang, $segment, $downloads, $publicUrl): void {
    ?>
<section class="section first stats-page">
  <div class="wrap">
    <h1><?= e(t('stats_h1')) ?></h1>
    <p class="lead"><?= e(t('stats_lead')) ?></p>
    <?= survey_presentation_stats($rows, $questions, $lang, [
        'baseUrl' => Site::url('stats', $lang),
        'segment' => $segment,
        'qrUrl' => $publicUrl,
        'downloads' => $downloads,
    ]) ?>
    <p class="note stats-foot-note"><?= e(t('stats_privacy_note')) ?></p>
  </div>
</section>
    <?php
});
