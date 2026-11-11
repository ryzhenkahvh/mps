<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/** Draws a page of plain text: a title, the date of the last change, headed paragraphs. */
function legal_page(array $doc): void
{
    layout($doc['title'] . ' — MPS', $doc['lead'], static function () use ($doc): void {
        ?>
<section class="section first">
  <div class="wrap narrow prose">
    <h1><?= e($doc['title']) ?></h1>
    <p class="note"><?= e($doc['updated']) ?></p>
    <p class="lead"><?= e($doc['lead']) ?></p>
    <?php foreach ($doc['parts'] as [$heading, $paragraphs]): ?>
      <h2><?= e($heading) ?></h2>
      <?php foreach ($paragraphs as $p): ?>
        <?php if (is_array($p)): ?>
          <ul><?php foreach ($p as $item): ?><li><?= e($item) ?></li><?php endforeach; ?></ul>
        <?php else: ?>
          <p><?= e($p) ?></p>
        <?php endif; ?>
      <?php endforeach; ?>
    <?php endforeach; ?>
  </div>
</section>
        <?php
    });
}
