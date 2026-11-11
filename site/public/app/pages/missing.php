<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

layout(t('missing_title'), '', static function (): void {
    ?>
<section class="section first">
  <div class="wrap narrow">
    <h1><?= e(t('missing_h1')) ?></h1>
    <p class="lead"><?= e(t('missing_lead')) ?></p>
    <a class="button primary" href="<?= e(Site::url('home')) ?>"><?= e(t('back_home')) ?></a>
  </div>
</section>
    <?php
}, ['noindex' => true]);
