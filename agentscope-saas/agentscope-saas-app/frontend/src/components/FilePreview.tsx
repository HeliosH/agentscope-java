import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  ChevronLeft,
  ChevronRight,
  Download,
  FileWarning,
  LoaderCircle,
  X,
} from 'lucide-react';
import type { PresentationData, SlideData } from '@office-kit/pptx';
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { downloadCurrentFile, downloadFileVersion } from '../api/workspace';

export interface PreviewFile {
  path: string;
  name?: string;
  versionId?: string;
  sizeBytes?: number;
}

interface PreviewPaneProps {
  agentId: string;
  file: PreviewFile;
  refreshKey?: number;
  className?: string;
}

interface PreviewDialogProps {
  agentId: string;
  file: PreviewFile | null;
  onClose: () => void;
}

type PreviewKind = 'audio' | 'docx' | 'image' | 'pdf' | 'pptx' | 'text' | 'video' | 'xlsx' | 'unsupported';

const MIB = 1024 * 1024;
const PREVIEW_LIMITS: Record<PreviewKind, number> = {
  audio: 200 * MIB,
  docx: 30 * MIB,
  image: 200 * MIB,
  pdf: 200 * MIB,
  pptx: 80 * MIB,
  text: 5 * MIB,
  video: 200 * MIB,
  xlsx: 30 * MIB,
  unsupported: 0,
};

const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'avif', 'svg']);
const AUDIO_EXTENSIONS = new Set(['mp3', 'm4a', 'wav', 'flac', 'ogg', 'opus', 'aac']);
const VIDEO_EXTENSIONS = new Set(['mp4', 'mov', 'webm', 'ogv']);
const UNSUPPORTED_EXTENSIONS = new Set([
  '7z', 'a', 'avi', 'bin', 'bz2', 'class', 'dat', 'db', 'dll', 'doc', 'ear', 'eot',
  'exe', 'heic', 'ico', 'jar', 'mkv', 'mdb', 'odg', 'odp', 'ods', 'odt', 'otf',
  'ppt', 'pyc', 'pyo', 'rar', 'so', 'sqlite', 'sqlite3', 'tar', 'tbz', 'tbz2',
  'gz', 'tgz', 'tif', 'tiff', 'ttf', 'war', 'wasm', 'wmv', 'woff', 'woff2', 'xls', 'xz', 'zip',
]);

function extension(file: PreviewFile): string {
  const name = file.name || leaf(file.path);
  const match = /\.([^.]+)$/.exec(name);
  return match?.[1].toLowerCase() ?? '';
}

function previewKind(file: PreviewFile): PreviewKind {
  const ext = extension(file);
  if (IMAGE_EXTENSIONS.has(ext)) return 'image';
  if (AUDIO_EXTENSIONS.has(ext)) return 'audio';
  if (VIDEO_EXTENSIONS.has(ext)) return 'video';
  if (ext === 'pdf') return 'pdf';
  if (ext === 'docx') return 'docx';
  if (ext === 'pptx') return 'pptx';
  if (ext === 'xlsx') return 'xlsx';
  if (UNSUPPORTED_EXTENSIONS.has(ext)) return 'unsupported';
  return 'text';
}

function leaf(path: string): string {
  const normalized = path.replace(/\\/g, '/');
  return normalized.split('/').filter(Boolean).pop() || 'file';
}

function displayName(file: PreviewFile): string {
  return file.name || leaf(file.path);
}

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  const amount = value / 1024 ** index;
  return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
}

function mimeType(file: PreviewFile): string {
  const types: Record<string, string> = {
    aac: 'audio/aac',
    avif: 'image/avif',
    bmp: 'image/bmp',
    flac: 'audio/flac',
    gif: 'image/gif',
    jpeg: 'image/jpeg',
    jpg: 'image/jpeg',
    m4a: 'audio/mp4',
    mov: 'video/quicktime',
    mp3: 'audio/mpeg',
    mp4: 'video/mp4',
    ogg: 'audio/ogg',
    ogv: 'video/ogg',
    opus: 'audio/ogg',
    pdf: 'application/pdf',
    png: 'image/png',
    svg: 'image/svg+xml',
    wav: 'audio/wav',
    webm: 'video/webm',
    webp: 'image/webp',
  };
  return types[extension(file)] ?? 'application/octet-stream';
}

async function loadFile(agentId: string, file: PreviewFile): Promise<Blob> {
  return file.versionId
    ? downloadFileVersion(agentId, file.versionId)
    : downloadCurrentFile(agentId, file.path);
}

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function usePreviewBlob(agentId: string, file: PreviewFile, refreshKey?: number) {
  const kind = previewKind(file);
  const [blob, setBlob] = useState<Blob | null>(null);
  const [loading, setLoading] = useState(kind !== 'unsupported');
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setBlob(null);
    setError('');
    if (kind === 'unsupported') {
      setLoading(false);
      return () => { cancelled = true; };
    }
    const limit = PREVIEW_LIMITS[kind];
    if (file.sizeBytes && file.sizeBytes > limit) {
      setLoading(false);
      setError(`This ${formatBytes(file.sizeBytes)} file exceeds the ${formatBytes(limit)} preview limit.`);
      return () => { cancelled = true; };
    }
    setLoading(true);
    loadFile(agentId, file)
      .then(result => {
        if (result.size > limit) {
          throw new Error(`This ${formatBytes(result.size)} file exceeds the ${formatBytes(limit)} preview limit.`);
        }
        if (!cancelled) setBlob(result);
      })
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to load file preview.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [agentId, file.path, file.versionId, file.sizeBytes, kind, refreshKey]);

  return { blob, error, kind, loading };
}

function useObjectUrl(blob: Blob, file: PreviewFile): string {
  const [url, setUrl] = useState('');
  useEffect(() => {
    const typedBlob = new Blob([blob], { type: mimeType(file) });
    const next = URL.createObjectURL(typedBlob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [blob, file.path, file.name]);
  return url;
}

function PreviewNotice({ children }: { children: string }) {
  return (
    <div className="file-preview__notice">
      <FileWarning size={24} />
      <span>{children}</span>
    </div>
  );
}

function TextPreview({ blob }: { blob: Blob }) {
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    blob.arrayBuffer()
      .then(buffer => {
        const bytes = new Uint8Array(buffer);
        if (bytes.slice(0, Math.min(bytes.length, 4096)).includes(0)) {
          throw new Error('This file appears to contain binary data and cannot be shown as text.');
        }
        if (!cancelled) setText(new TextDecoder().decode(bytes));
      })
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to decode text file.');
      });
    return () => { cancelled = true; };
  }, [blob]);
  if (error) return <PreviewNotice>{error}</PreviewNotice>;
  return <pre className="file-preview__text">{text}</pre>;
}

function NativePreview({ blob, file, kind }: { blob: Blob; file: PreviewFile; kind: 'audio' | 'image' | 'video' }) {
  const url = useObjectUrl(blob, file);
  if (!url) return null;
  if (kind === 'image') {
    return <div className="file-preview__media-stage"><img src={url} alt={displayName(file)} /></div>;
  }
  if (kind === 'audio') {
    return <div className="file-preview__media-stage"><audio src={url} controls preload="metadata" /></div>;
  }
  if (kind === 'video') {
    return <div className="file-preview__media-stage"><video src={url} controls preload="metadata" /></div>;
  }
  return null;
}

function PdfPreview({ blob }: { blob: Blob }) {
  const stageRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [stageWidth, setStageWidth] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return undefined;
    const measure = () => setStageWidth(Math.round(stage.clientWidth));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(stage);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let cancelled = false;
    let loadedDocument: PDFDocumentProxy | null = null;
    let loadingTask: PDFDocumentLoadingTask | null = null;
    setDocument(null);
    setPageIndex(0);
    setLoading(true);
    setError('');
    Promise.all([blob.arrayBuffer(), import('pdfjs-dist')])
      .then(async ([buffer, pdfjs]) => {
        pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;
        loadingTask = pdfjs.getDocument({ data: new Uint8Array(buffer), isEvalSupported: false });
        loadedDocument = await loadingTask.promise;
        if (cancelled) {
          await loadedDocument.destroy();
        } else {
          setDocument(loadedDocument);
        }
      })
      .catch(cause => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : 'Failed to load PDF document.');
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
      if (loadingTask && !loadedDocument) void loadingTask.destroy();
      if (loadedDocument) void loadedDocument.destroy();
    };
  }, [blob]);

  useEffect(() => {
    if (!document || !stageWidth) return undefined;
    let cancelled = false;
    let renderTask: { cancel: () => void; promise: Promise<void> } | null = null;
    setLoading(true);
    setError('');
    document.getPage(pageIndex + 1)
      .then(page => {
        if (cancelled) return undefined;
        const canvas = canvasRef.current;
        if (!canvas) return undefined;
        const baseViewport = page.getViewport({ scale: 1 });
        const cssScale = Math.min(1.75, Math.max(0.25, (stageWidth - 40) / baseViewport.width));
        const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
        const viewport = page.getViewport({ scale: cssScale * pixelRatio });
        canvas.width = Math.floor(viewport.width);
        canvas.height = Math.floor(viewport.height);
        canvas.style.width = `${Math.floor(viewport.width / pixelRatio)}px`;
        canvas.style.height = `${Math.floor(viewport.height / pixelRatio)}px`;
        const context = canvas.getContext('2d', { alpha: false });
        if (!context) throw new Error('Canvas rendering is not available in this browser.');
        renderTask = page.render({ canvas, canvasContext: context, viewport, background: '#ffffff' });
        return renderTask.promise;
      })
      .then(() => {
        if (!cancelled) setLoading(false);
      })
      .catch(cause => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : 'Failed to render PDF page.');
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
      renderTask?.cancel();
    };
  }, [document, pageIndex, stageWidth]);

  if (error) return <PreviewNotice>{error}</PreviewNotice>;
  return (
    <div className="file-preview__pdf">
      <div ref={stageRef} className="file-preview__pdf-stage">
        <canvas ref={canvasRef} />
        {loading && <div className="file-preview__rendering"><LoaderCircle className="is-spinning" size={20} /> Rendering PDF...</div>}
      </div>
      {document && (
        <div className="file-preview__pager">
          <button type="button" className="icon-button" disabled={pageIndex === 0} onClick={() => setPageIndex(index => index - 1)} title="Previous page" aria-label="Previous page">
            <ChevronLeft size={16} />
          </button>
          <span>Page {pageIndex + 1} of {document.numPages}</span>
          <button type="button" className="icon-button" disabled={pageIndex === document.numPages - 1} onClick={() => setPageIndex(index => index + 1)} title="Next page" aria-label="Next page">
            <ChevronRight size={16} />
          </button>
        </div>
      )}
    </div>
  );
}

function DocxPreview({ blob }: { blob: Blob }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    const container = containerRef.current;
    if (!container) return () => { cancelled = true; };
    container.replaceChildren();
    import('docx-preview')
      .then(({ renderAsync }) => renderAsync(blob, container, container, {
        breakPages: true,
        experimental: false,
        ignoreFonts: false,
        renderAltChunks: false,
        renderComments: false,
        useBase64URL: true,
      }))
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to render Word document.');
      });
    return () => {
      cancelled = true;
      container.replaceChildren();
    };
  }, [blob]);
  if (error) return <PreviewNotice>{error}</PreviewNotice>;
  return (
    <div
      ref={containerRef}
      className="file-preview__docx"
      onClick={event => {
        if ((event.target as HTMLElement).closest('a')) event.preventDefault();
      }}
    />
  );
}

type PptxDeck = {
  presentation: PresentationData;
  slides: ReadonlyArray<SlideData>;
  render: (presentation: PresentationData, slide: SlideData) => string;
};

function PptxPreview({ blob }: { blob: Blob }) {
  const [deck, setDeck] = useState<PptxDeck | null>(null);
  const [slideIndex, setSlideIndex] = useState(0);
  const [slideDocument, setSlideDocument] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    setDeck(null);
    setSlideIndex(0);
    setError('');
    Promise.all([import('@office-kit/pptx'), import('@office-kit/pptx-preview')])
      .then(async ([pptx, preview]) => {
        const presentation = await pptx.loadPresentation(blob);
        const slides = pptx.getSlides(presentation);
        if (!slides.length) throw new Error('This presentation contains no slides.');
        if (!cancelled) setDeck({ presentation, slides, render: preview.renderSlideToSvg });
      })
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to render presentation.');
      });
    return () => { cancelled = true; };
  }, [blob]);

  useEffect(() => {
    setSlideDocument('');
    if (!deck) return;
    try {
      const svg = deck.render(deck.presentation, deck.slides[slideIndex]);
      setSlideDocument(`<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:"><style>html,body{width:100%;height:100%;margin:0;background:#e8e9e6;overflow:hidden}body{display:grid;place-items:center}svg{display:block;max-width:100%;max-height:100%;background:white;box-shadow:0 2px 16px rgba(0,0,0,.12)}</style></head><body>${svg}</body></html>`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Failed to render presentation slide.');
    }
  }, [deck, slideIndex]);

  if (error) return <PreviewNotice>{error}</PreviewNotice>;
  if (!deck || !slideDocument) return <div className="file-preview__loading"><LoaderCircle className="is-spinning" size={20} /> Rendering presentation...</div>;
  return (
    <div className="file-preview__pptx">
      <iframe title={`Slide ${slideIndex + 1}`} sandbox="" srcDoc={slideDocument} />
      <div className="file-preview__pager">
        <button type="button" className="icon-button" disabled={slideIndex === 0} onClick={() => setSlideIndex(index => index - 1)} title="Previous slide" aria-label="Previous slide">
          <ChevronLeft size={16} />
        </button>
        <span>Slide {slideIndex + 1} of {deck.slides.length}</span>
        <button type="button" className="icon-button" disabled={slideIndex === deck.slides.length - 1} onClick={() => setSlideIndex(index => index + 1)} title="Next slide" aria-label="Next slide">
          <ChevronRight size={16} />
        </button>
      </div>
    </div>
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (value instanceof Date) return value.toLocaleString();
  return String(value);
}

function columnLabel(index: number): string {
  let label = '';
  let current = index + 1;
  while (current > 0) {
    const remainder = (current - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    current = Math.floor((current - 1) / 26);
  }
  return label;
}

function XlsxPreview({ blob }: { blob: Blob }) {
  const [sheets, setSheets] = useState<Array<{ sheet: string; data: unknown[][] }>>([]);
  const [sheetIndex, setSheetIndex] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    setSheets([]);
    setSheetIndex(0);
    setError('');
    import('read-excel-file/browser')
      .then(({ default: readWorkbook }) => readWorkbook(blob))
      .then(result => {
        if (!cancelled) setSheets(result as Array<{ sheet: string; data: unknown[][] }>);
      })
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to render spreadsheet.');
      });
    return () => { cancelled = true; };
  }, [blob]);

  if (error) return <PreviewNotice>{error}</PreviewNotice>;
  if (!sheets.length) return <div className="file-preview__loading"><LoaderCircle className="is-spinning" size={20} /> Rendering spreadsheet...</div>;
  const active = sheets[sheetIndex];
  const rows = active.data.slice(0, 500);
  const columnCount = Math.min(60, rows.reduce((max, row) => Math.max(max, row.length), 0));
  return (
    <div className="file-preview__xlsx">
      <div className="file-preview__sheet-tabs" role="tablist" aria-label="Workbook sheets">
        {sheets.map((sheet, index) => (
          <button key={`${sheet.sheet}-${index}`} type="button" role="tab" aria-selected={sheetIndex === index} className={sheetIndex === index ? 'is-active' : ''} onClick={() => setSheetIndex(index)}>
            {sheet.sheet}
          </button>
        ))}
      </div>
      <div className="file-preview__table-wrap">
        <table>
          <thead>
            <tr>
              <th />
              {Array.from({ length: columnCount }, (_, columnIndex) => (
                <th key={columnIndex}>{columnLabel(columnIndex)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                <th>{rowIndex + 1}</th>
                {Array.from({ length: columnCount }, (_, columnIndex) => (
                  <td key={columnIndex}>{formatCell(row[columnIndex])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && <div className="file-preview__notice">This sheet is empty.</div>}
      </div>
      {(active.data.length > rows.length || active.data.some(row => row.length > columnCount)) && (
        <div className="file-preview__limit-note">Preview is limited to 500 rows and 60 columns.</div>
      )}
    </div>
  );
}

function PreviewContent({ blob, file, kind }: { blob: Blob; file: PreviewFile; kind: PreviewKind }) {
  if (kind === 'text') return <TextPreview blob={blob} />;
  if (kind === 'docx') return <DocxPreview blob={blob} />;
  if (kind === 'pptx') return <PptxPreview blob={blob} />;
  if (kind === 'xlsx') return <XlsxPreview blob={blob} />;
  if (kind === 'pdf') return <PdfPreview blob={blob} />;
  if (kind === 'audio' || kind === 'image' || kind === 'video') {
    return <NativePreview blob={blob} file={file} kind={kind} />;
  }
  return <PreviewNotice>Preview is not available for this file type. Download the file to open it.</PreviewNotice>;
}

export function FilePreviewPane({ agentId, file, refreshKey, className = '' }: PreviewPaneProps) {
  const { blob, error, kind, loading } = usePreviewBlob(agentId, file, refreshKey);
  return (
    <div className={`file-preview${className ? ` ${className}` : ''}`}>
      {loading && <div className="file-preview__loading"><LoaderCircle className="is-spinning" size={20} /> Loading preview...</div>}
      {!loading && error && <PreviewNotice>{error}</PreviewNotice>}
      {!loading && !error && kind === 'unsupported' && (
        <PreviewNotice>Preview is not available for this file type. Download the file to open it.</PreviewNotice>
      )}
      {!loading && !error && blob && <PreviewContent blob={blob} file={file} kind={kind} />}
    </div>
  );
}

export function FilePreviewDialog({ agentId, file, onClose }: PreviewDialogProps) {
  const [downloadError, setDownloadError] = useState('');
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    if (!file) return undefined;
    setDownloadError('');
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [file, onClose]);

  if (!file) return null;

  async function download() {
    if (!file || downloading) return;
    setDownloading(true);
    setDownloadError('');
    try {
      saveBlob(await loadFile(agentId, file), displayName(file));
    } catch (cause) {
      setDownloadError(cause instanceof Error ? cause.message : 'File download failed.');
    } finally {
      setDownloading(false);
    }
  }

  return createPortal(
    <div className="file-preview-dialog" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <section className="file-preview-dialog__panel" role="dialog" aria-modal="true" aria-labelledby="file-preview-title">
        <header className="file-preview-dialog__header">
          <div>
            <h2 id="file-preview-title">{displayName(file)}</h2>
            <p title={file.path}>{file.path}{file.sizeBytes ? ` · ${formatBytes(file.sizeBytes)}` : ''}</p>
          </div>
          {downloadError && <span className="file-preview-dialog__error">{downloadError}</span>}
          <button className="quiet-button" type="button" disabled={downloading} onClick={() => void download()}>
            {downloading ? <LoaderCircle className="is-spinning" size={15} /> : <Download size={15} />}
            Download
          </button>
          <button className="icon-button" type="button" title="Close preview" aria-label="Close preview" onClick={onClose} autoFocus>
            <X size={17} />
          </button>
        </header>
        <FilePreviewPane agentId={agentId} file={file} />
      </section>
    </div>,
    document.body,
  );
}
