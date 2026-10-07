#!/usr/bin/env node
import { readFile } from 'node:fs/promises';

if (process.argv.length < 3) {
  console.error('Usage: node scripts/app-sharing/benchmark-report.mjs <report.json> [report.json ...]');
  process.exitCode = 1;
} else {
  const rounded = value => Number.isFinite(value) ? value.toFixed(2) : '—';
  console.log('| Case | Phase | Valid | Source/presented FPS | ≥80% target | Latency p50/p95 ms | Prepare/upload p95 ms | Encode/decode avg ms | Jitter actual/target/min ms | Render interval mean/std ms | Freezes | Playback displayed/dropped | Observer p95 ms | Text PSNR p50 dB | Decoded widths |');
  console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
  for (const file of process.argv.slice(2)) {
    const report = JSON.parse(await readFile(file, 'utf8'));
    if (!['boss-sharing-benchmark/1', 'boss-sharing-benchmark/2'].includes(report.schema)) throw new Error('Unsupported benchmark schema');
    for (const phase of report.phases) for (const [index, viewer] of phase.viewers.entries()) {
      const scenario = report.scenario;
      const attained = ['source-pause', 'reader-pause'].includes(phase.name) ? 'n/a' : viewer.presentedFps >= scenario.fps * 0.8 ? 'yes' : 'no';
      console.log(`| ${report.schema.endsWith('/1') ? 'v1-marker' : 'v2-compositor'}:${scenario.transport ?? 'legacy-transport'}/${scenario.viewerPlacement ?? 'legacy'}/${scenario.sourceRenderer ?? 'legacy'}/${scenario.frameDelivery ?? 'polling'}/${scenario.format}/${scenario.fps}/${scenario.contentHint ?? 'motion'}/read${scenario.markerEvery ?? 'legacy'}/jitter${scenario.jitterBufferTargetMs ?? 'default'}:${scenario.receiverJitterTargets?.[index]?.status ?? 'legacy'}/peer${index + 1} | ${phase.name} | ${report.valid} | ${rounded(phase.sourceFps)}/${rounded(viewer.presentedFps)} | ${attained} | ${rounded(viewer.latencyMs.p50)}/${rounded(viewer.latencyMs.p95)} | ${rounded(phase.preparationMs.p95)}/${rounded(phase.rawUploadMs.p95)} | ${rounded(viewer.averageEncodeMs)}/${rounded(viewer.averageDecodeMs)} | ${rounded(viewer.averageJitterBufferMs)}/${rounded(viewer.averageJitterBufferTargetMs)}/${rounded(viewer.averageJitterBufferMinimumMs)} | ${rounded(viewer.renderedFrameIntervalMeanMs)}/${rounded(viewer.renderedFrameIntervalStdDevMs)} | ${viewer.receiverFreezes ?? '—'} | ${viewer.playbackDisplayedFrames ?? '—'}/${viewer.playbackDroppedFrames ?? '—'} | ${rounded(viewer.observerCostMs?.p95)} | ${rounded(viewer.textLumaPsnrDb.p50)} | ${viewer.decodedWidths.join(',')} |`);
    }
  }
}
