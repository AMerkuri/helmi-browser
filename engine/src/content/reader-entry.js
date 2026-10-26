// Reader mode: the page's article as clean HTML.
// Bundled with @mozilla/readability into dist/reader.js by build.mjs. Unlike serene.js it does
// not run at document start: the app evaluates it on demand and reads `__sereneReader()`.
// The result is page content, so whatever shows it treats it as untrusted (the app's reader
// view runs no scripts).
import { Readability, isProbablyReaderable } from '@mozilla/readability';

const DROP = 'data-serene-reader-drop';
// Never shown in the reader view: active or embedded content and form controls.
const STRIP = 'script, style, link, meta, iframe, frame, object, embed, applet, form, input, button, select, textarea, dialog, noscript, template, svg';

/**
 * Marks what is not part of the page's text on the live page: the overlays Serene hid and
 * fixed-position layers (banners, chat bubbles, sticky bars). Returns the marked elements so the
 * marks can come off again after the clone. Other hidden elements stay: collapsed sections
 * (mobile Wikipedia) are part of the article, and Readability drops hidden menus itself.
 */
function markHidden(doc) {
  const marked = [];
  const view = doc.defaultView;
  const visit = (parent) => {
    for (const el of parent.children) {
      if (el.hasAttribute('data-serene') || view.getComputedStyle(el).position === 'fixed') {
        // The mark on the root removes the whole subtree.
        el.setAttribute(DROP, '');
        marked.push(el);
      } else {
        visit(el);
      }
    }
  };
  visit(doc.body);
  return marked;
}

function sanitize(root) {
  for (const el of root.querySelectorAll(STRIP)) el.remove();
  for (const el of root.querySelectorAll('*')) {
    for (const a of [...el.attributes]) {
      const v = a.value.trim().toLowerCase();
      if (a.name.startsWith('on') || a.name === 'style' || a.name === 'srcdoc' || ((a.name === 'href' || a.name === 'src') && /^(javascript|vbscript|data:text)/.test(v))) {
        el.removeAttribute(a.name);
      }
    }
  }
}

const countWords = (s) => (s.match(/[\p{L}\p{N}]+/gu) || []).length;

export function extract(doc = document) {
  const marked = doc.body ? markHidden(doc) : [];
  let page;
  try {
    page = doc.cloneNode(true);
  } finally {
    for (const el of marked) el.removeAttribute(DROP);
  }
  for (const el of page.querySelectorAll(`[${DROP}]`)) el.remove();

  // Collapsed sections that find-in-page can open (mobile Wikipedia) are part of the article;
  // Readability drops anything [hidden].
  for (const el of page.querySelectorAll('[hidden="until-found"]')) el.removeAttribute('hidden');

  const readerable = isProbablyReaderable(page);
  const article = new Readability(page, { charThreshold: 400 }).parse();
  const base = {
    url: doc.location ? doc.location.href : '',
    lang: (article && article.lang) || doc.documentElement.lang || '',
    dir: (article && article.dir) || doc.dir || '',
  };
  if (!article || !article.content) {
    return { ...base, article: false, title: doc.title || '', byline: '', siteName: '', excerpt: '', html: '', words: 0 };
  }
  const tpl = doc.createElement('template');
  tpl.innerHTML = article.content;
  sanitize(tpl.content);
  const words = countWords(tpl.content.textContent);
  const div = doc.createElement('div');
  div.appendChild(tpl.content);
  return {
    ...base,
    // Readability always finds something; on a page that is not an article (a shop, a front
    // page) that is a fragment.
    article: readerable,
    title: article.title || doc.title || '',
    byline: article.byline || '',
    siteName: article.siteName || '',
    excerpt: article.excerpt || '',
    publishedTime: article.publishedTime || '',
    html: div.innerHTML,
    words,
  };
}

globalThis.__sereneReader = () => {
  try {
    return extract(document);
  } catch (e) {
    return { error: String(e && e.message || e) };
  }
};
