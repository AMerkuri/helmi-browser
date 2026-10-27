// Per-site verdict cache. Key: eTLD+1, then the candidate's stable selector signature.
// A decision made once (by heuristics or the model) becomes a local rule for later visits.
import fs from 'node:fs';
import { getDomain } from 'tldts';

export const siteOf = (url) => getDomain(url) || new URL(url).hostname;

export class VerdictCache {
  constructor(file = null) {
    this.file = file;
    this.sites = {};
    if (file && fs.existsSync(file)) this.sites = JSON.parse(fs.readFileSync(file, 'utf8'));
  }

  rulesFor(url) { return this.sites[siteOf(url)] ?? []; }

  put(url, rule) {
    const site = siteOf(url);
    const list = (this.sites[site] ??= []);
    const i = list.findIndex((r) => r.sig === rule.sig);
    const entry = { sig: rule.sig, label: rule.label, action: rule.action, buttonLabel: rule.buttonLabel, source: rule.source, was: rule.was, ts: Date.now() };
    if (i >= 0) list[i] = entry; else list.push(entry);
    this.save();
  }

  /** User pressed undo: remember the element as content for this site. `was` keeps what it was
   *  taken for, so the site sheet can name it and offer to hide it again. */
  undo(url, sig, was = null) { this.put(url, { sig, label: 'content', action: 'ignore', source: 'user', was: was ?? undefined }); }

  /** Elements the user undid on this site. */
  undone(url) { return this.rulesFor(url).filter((r) => r.source === 'user'); }

  /** Drop one rule, so the element is judged afresh on the next load (reverses an undo). */
  remove(url, sig) {
    const site = siteOf(url);
    const list = this.sites[site];
    if (!list) return;
    this.sites[site] = list.filter((r) => r.sig !== sig);
    if (!this.sites[site].length) delete this.sites[site];
    this.save();
  }

  save() { if (this.file) fs.writeFileSync(this.file, JSON.stringify(this.sites, null, 1)); }

  /** Cached hide verdicts as cosmetic CSS, injected at page start so there is no flash. With
   *  parkCookies (autoconsent on) cookie banners are left out: they go to cookieSelectorsFor. */
  cssFor(url, { parkCookies = false } = {}) {
    return this.hideRules(url)
      .filter((r) => !(parkCookies && r.label === 'cookie_consent'))
      .map((r) => `${r.sig}{display:none!important}`).join('\n');
  }

  /** Cached cookie banners as consent selectors: parked while autoconsent tries to reject them,
   *  which it cannot do to a banner hidden from the start (techspot, #24). */
  cookieSelectorsFor(url) { return this.hideRules(url).filter((r) => r.label === 'cookie_consent').map((r) => r.sig); }

  hideRules(url) { return this.rulesFor(url).filter((r) => r.action === 'hide' && /[#.]/.test(r.sig)); }
}
