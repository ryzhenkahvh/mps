<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

$apk = Site::apk();
layout(t('download_title'), t('download_description'), static function () use ($apk): void {
    ?>
<section class="section first">
  <div class="wrap narrow">
    <h1><?= e(t('download_h1')) ?></h1>
    <?php if ($apk): ?>
      <div class="download-box">
        <a class="button primary big" href="/download/get.php" rel="nofollow"><?= e(t('download_button')) ?></a>
        <p class="note"><?= e(t('download_meta', $apk['version'] ?: '1.0', $apk['megabytes'], $apk['date'])) ?></p>
        <p class="note"><?= e(t('install_requirements')) ?></p>
      </div>
    <?php else: ?>
      <div class="download-box">
        <h2><?= e(t('download_soon_h')) ?></h2>
        <p><?= e(t('download_soon')) ?></p>
        <a class="button primary" href="<?= e(Site::url('survey')) ?>"><?= e(t('cta_button')) ?></a>
      </div>
    <?php endif; ?>

    <h2><?= e(t('install_h2')) ?></h2>
    <ol class="steps">
      <?php foreach (Site::list('install') as $step): ?>
        <li><?= e($step) ?></li>
      <?php endforeach; ?>
    </ol>
    <?php if ($apk && $apk['sha256'] !== ''): ?>
      <h2><?= e(t('checksum')) ?></h2>
      <p><code class="hash"><?= e($apk['sha256']) ?></code></p>
      <p class="note"><?= e(t('checksum_note')) ?></p>
    <?php endif; ?>
  </div>
</section>
    <?php
});
