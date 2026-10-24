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
    const entry = { sig: rule.sig, label: rule.label, action: rule.action, buttonLabel: rule.buttonLabel, source: rule.source, ts: Date.now() };
    if (i >= 0) list[i] = entry; else list.push(entry);
    this.save();
  }

  /** User pressed undo: remember the element as content for this site. */
  undo(url, sig) { this.put(url, { sig, label: 'content', action: 'ignore', source: 'user' }); }

  save() { if (this.file) fs.writeFileSync(this.file, JSON.stringify(this.sites, null, 1)); }

  /** Cached hide verdicts as cosmetic CSS, injected at page start so there is no flash. */
  cssFor(url) {
    return this.rulesFor(url)
      .filter((r) => r.action === 'hide' && /[#.]/.test(r.sig))
      .map((r) => `${r.sig}{display:none!important}`).join('\n');
  }
}
