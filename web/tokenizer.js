// BERT "uncased" WordPiece tokenizer, a port of the app's WordPieceTokenizer.kt so the
// browser produces the same token ids as the phone and the nightly pipeline.

const PUNCT = /\p{P}/u;
const CONTROL = /\p{Cc}/u;
const WHITESPACE = /\s/u;
const MARKS = /\p{Mn}+/gu;

function isCjk(cp) {
  return (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0x3400 && cp <= 0x4dbf) ||
    (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x2f800 && cp <= 0x2fa1f) ||
    (cp >= 0x20000 && cp <= 0x2a6df);
}

function isPunctuation(ch) {
  const cp = ch.codePointAt(0);
  if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
    return true;
  }
  return PUNCT.test(ch);
}

export class WordPieceTokenizer {
  constructor(vocabText, maxLen = 256) {
    this.maxLen = maxLen;
    this.vocab = new Map();
    vocabText.split('\n').forEach((line, i) => {
      const token = line.replace(/\r$/, '');
      if (token !== '' || i === 0) this.vocab.set(token, i);
    });
    this.cls = this.vocab.get('[CLS]');
    this.sep = this.vocab.get('[SEP]');
    this.unk = this.vocab.get('[UNK]');
  }

  encode(text) {
    const ids = [this.cls];
    outer: for (const word of this.basicTokenize(text)) {
      for (const id of this.wordPiece(word)) {
        if (ids.length >= this.maxLen - 1) break outer;
        ids.push(id);
      }
    }
    ids.push(this.sep);
    return ids;
  }

  basicTokenize(text) {
    let cleaned = '';
    for (const ch of text) {
      const cp = ch.codePointAt(0);
      if (WHITESPACE.test(ch)) cleaned += ' ';
      else if (cp === 0 || cp === 0xfffd || CONTROL.test(ch)) continue;
      else if (isCjk(cp)) cleaned += ` ${ch} `;
      else cleaned += ch;
    }
    const normalized = cleaned.toLowerCase().normalize('NFD').replace(MARKS, '');

    const tokens = [];
    for (const chunk of normalized.split(' ')) {
      if (!chunk) continue;
      let current = '';
      for (const ch of chunk) {
        if (isPunctuation(ch)) {
          if (current) { tokens.push(current); current = ''; }
          tokens.push(ch);
        } else {
          current += ch;
        }
      }
      if (current) tokens.push(current);
    }
    return tokens;
  }

  wordPiece(word) {
    if (word.length > 100) return [this.unk];
    const pieces = [];
    let start = 0;
    while (start < word.length) {
      let end = word.length;
      let found;
      while (start < end) {
        const sub = (start > 0 ? '##' : '') + word.substring(start, end);
        const id = this.vocab.get(sub);
        if (id !== undefined) { found = id; break; }
        end--;
      }
      if (found === undefined) return [this.unk];
      pieces.push(found);
      start = end;
    }
    return pieces;
  }
}
