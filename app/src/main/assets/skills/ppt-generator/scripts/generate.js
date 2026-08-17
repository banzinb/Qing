#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const PptxGenJS = require('pptxgenjs');

const PALETTES = {
  'midnight-executive': {
    name: '午夜商务', primary: '1E2761', secondary: 'CADCFC', accent: 'FFFFFF',
    bgLight: 'FFFFFF', bgDark: '0D1117', text: '1E2761', textLight: '6B7280'
  },
  'tech-dark': {
    name: '科技深空', primary: '0D1117', secondary: '161B22', accent: '58A6FF',
    bgLight: 'FFFFFF', bgDark: '0D1117', text: 'F0F6FC', textLight: '8B949E'
  },
  'coral-energy': {
    name: '珊瑚活力', primary: 'F96167', secondary: 'F9E795', accent: '2F3C7E',
    bgLight: 'FFFFFF', text: '1F2937', textLight: '6B7280'
  },
  'warm-terracotta': {
    name: '暖陶简约', primary: 'B85042', secondary: 'E7E8D1', accent: 'A7BEAE',
    bgLight: 'FDFBF7', text: '3D3D3D', textLight: '6B7280'
  },
  'ocean-gradient': {
    name: '海洋渐变', primary: '065A82', secondary: '1C7293', accent: '21295C',
    bgLight: 'FFFFFF', bgDark: '065A82', text: '065A82', textLight: '6B7280'
  },
  'charcoal-minimal': {
    name: '炭灰极简', primary: '36454F', secondary: 'F2F2F2', accent: '212121',
    bgLight: 'FFFFFF', text: '36454F', textLight: '8B949E'
  },
  'teal-trust': {
    name: '青绿信任', primary: '028090', secondary: '00A896', accent: '02C39A',
    bgLight: 'FFFFFF', text: '065A60', textLight: '374151'
  },
  'berry-cream': {
    name: '莓果奶油', primary: '6D2E46', secondary: 'A26769', accent: 'ECE2D0',
    bgLight: 'FDFBF7', text: '3D3D3D', textLight: '6B7280'
  },
  'sage-calm': {
    name: '鼠尾草静', primary: '84B59F', secondary: '69A297', accent: '50808E',
    bgLight: 'F5F9F8', text: '3D4F4E', textLight: '6B7280'
  },
  'cherry-bold': {
    name: '樱桃大胆', primary: '990011', secondary: 'FCF6F5', accent: '2F3C7E',
    bgLight: 'FFFFFF', text: '1F2937', textLight: '6B7280'
  }
};

const FONT = 'Microsoft YaHei';

function cleanColor(value, fallback) {
  return String(value || fallback || '').replace(/^#/, '');
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

class QingPptGenerator {
  constructor(options = {}) {
    this.pptx = new PptxGenJS();
    this.pptx.layout = 'LAYOUT_16x9';
    this.language = options.language === 'en' ? 'en' : 'zh';
    this.palette = PALETTES[options.palette] || PALETTES['midnight-executive'];
    this.pptx.author = options.author || 'Qing';
    this.pptx.title = options.title || '演示文稿';
    this.pptx.company = options.company || '';
    this.pptx.subject = options.subject || '';
  }

  addCoverPage(page) {
    const slide = this.pptx.addSlide();
    slide.background = { color: cleanColor(this.palette.primary, '1E2761') };

    slide.addShape('ellipse', {
      x: 9.0, y: -1.6, w: 5.2, h: 5.2,
      fill: { color: cleanColor(this.palette.accent, 'FFFFFF'), transparency: 90 }
    });
    slide.addShape('ellipse', {
      x: -1.2, y: 5.2, w: 4.2, h: 4.2,
      fill: { color: cleanColor(this.palette.accent, 'FFFFFF'), transparency: 90 }
    });

    slide.addText(page.title || this.pptx.title, {
      x: 0.8, y: 2.4, w: 11.7, h: 1.6,
      fontSize: 46, fontFace: FONT, color: 'FFFFFF', bold: true, align: 'center', valign: 'middle',
      fit: 'shrink'
    });
    if (page.subtitle) {
      slide.addText(page.subtitle, {
        x: 1.5, y: 4.15, w: 10.3, h: 0.8,
        fontSize: 20, fontFace: FONT, color: cleanColor(this.palette.secondary, 'CADCFC'),
        align: 'center', valign: 'middle', fit: 'shrink'
      });
    }
    slide.addShape('rectangle', {
      x: 5.6, y: 5.45, w: 2.1, h: 0.06,
      fill: { color: cleanColor(this.palette.accent, 'FFFFFF') }
    });

    const highlights = asArray(page.items);
    if (highlights.length > 0) {
      const labels = highlights.slice(0, 3).map((item) => {
        const text = typeof item === 'string' ? item : (item.title || item.label || '');
        return text;
      });
      slide.addText(labels.map((label) => ({ text: label, options: { bullet: false } })), {
        x: 2.0, y: 5.8, w: 9.3, h: 0.7,
        fontSize: 12, fontFace: FONT, color: 'FFFFFF', align: 'center',
        paraSpaceAfter: 4, fit: 'shrink'
      });
    }
  }

  addTocPage(page) {
    const slide = this.pptx.addSlide();
    slide.background = { color: cleanColor(this.palette.bgLight || 'FFFFFF', 'FFFFFF') };

    slide.addText(page.title || (this.language === 'zh' ? '目录' : 'Contents'), {
      x: 0.8, y: 0.45, w: 11.7, h: 0.9,
      fontSize: 34, fontFace: FONT, color: cleanColor(this.palette.text || this.palette.primary, '1E2761'),
      bold: true, valign: 'middle'
    });
    slide.addShape('rectangle', {
      x: 0.8, y: 1.5, w: 0.1, h: 5.3,
      fill: { color: cleanColor(this.palette.primary, '1E2761') }
    });

    const items = asArray(page.items);
    const startY = 1.8;
    const itemHeight = Math.min(1.0, (items.length > 0 ? 4.9 / items.length : 0.9));
    items.forEach((item, index) => {
      const label = typeof item === 'string' ? item : (item.title || item.label || '');
      slide.addText(String(index + 1).padStart(2, '0'), {
        x: 1.25, y: startY + index * itemHeight, w: 0.7, h: 0.7,
        fontSize: 22, fontFace: FONT, color: cleanColor(this.palette.primary, '1E2761'),
        bold: true, valign: 'middle'
      });
      slide.addText(label, {
        x: 2.1, y: startY + index * itemHeight, w: 9.6, h: 0.7,
        fontSize: 18, fontFace: FONT, color: cleanColor(this.palette.text || '333333', '333333'),
        valign: 'middle', fit: 'shrink'
      });
      if (index < items.length - 1) {
        slide.addShape('rectangle', {
          x: 2.1, y: startY + index * itemHeight + 0.75, w: 9.4, h: 0.012,
          fill: { color: 'E5E5E5' }
        });
      }
    });
  }

  addContentPage(page) {
    const slide = this.pptx.addSlide();
    slide.background = { color: cleanColor(this.palette.bgLight || 'FFFFFF', 'FFFFFF') };

    slide.addShape('rectangle', {
      x: 0, y: 0, w: 13.33, h: 0.14,
      fill: { color: cleanColor(this.palette.primary, '1E2761') }
    });
    slide.addText(page.title || '要点', {
      x: 0.8, y: 0.45, w: 11.7, h: 0.85,
      fontSize: 30, fontFace: FONT, color: cleanColor(this.palette.primary, '1E2761'),
      bold: true, valign: 'middle', fit: 'shrink'
    });

    const items = asArray(page.items);
    const startY = 1.55;
    const areaHeight = 5.4;
    const itemHeight = Math.min(1.0, (items.length > 0 ? areaHeight / items.length : 0.9));
    const itemY = (index) => startY + index * itemHeight + 0.08;

    items.slice(0, 6).forEach((item, index) => {
      const isObject = typeof item === 'object' && item !== null;
      const title = isObject ? (item.title || '') : '';
      const body = isObject ? (item.body || '') : String(item);
      const y = itemY(index);

      slide.addShape('roundRect', {
        x: 0.8, y: y, w: 0.12, h: itemHeight - 0.22,
        fill: { color: cleanColor(this.palette.accent, 'FFFFFF') },
        rectRadius: 0.04
      });

      if (isObject && title) {
        slide.addText(title, {
          x: 1.15, y: y, w: 11.3, h: itemHeight * 0.34,
          fontSize: 17, fontFace: FONT, color: cleanColor(this.palette.text || this.palette.primary, '1E2761'),
          bold: true, valign: 'middle', fit: 'shrink'
        });
        slide.addText(body, {
          x: 1.15, y: y + itemHeight * 0.34, w: 11.3, h: itemHeight * 0.62,
          fontSize: 14, fontFace: FONT, color: cleanColor(this.palette.textLight || '6B7280', '6B7280'),
          valign: 'top', fit: 'shrink'
        });
      } else {
        slide.addText(body, {
          x: 1.15, y: y, w: 11.3, h: itemHeight - 0.2,
          fontSize: 16, fontFace: FONT, color: cleanColor(this.palette.text || '333333', '333333'),
          valign: 'middle', fit: 'shrink'
        });
      }
    });
  }

  addBigNumberPage(page) {
    const slide = this.pptx.addSlide();
    slide.background = { color: cleanColor(this.palette.bgLight || 'FFFFFF', 'FFFFFF') };

    slide.addText(page.title || (this.language === 'zh' ? '核心数据' : 'Key Numbers'), {
      x: 0.8, y: 0.45, w: 11.7, h: 0.85,
      fontSize: 30, fontFace: FONT, color: cleanColor(this.palette.primary, '1E2761'),
      bold: true, valign: 'middle'
    });

    const numbers = asArray(page.numbers).slice(0, 6);
    const cardWidth = 3.6;
    const cardHeight = 2.2;
    const gap = 0.42;
    const startX = 0.9;
    const startY = 1.8;
    const perRow = 3;

    numbers.forEach((item, index) => {
      const row = Math.floor(index / perRow);
      const col = index % perRow;
      const x = startX + col * (cardWidth + gap);
      const y = startY + row * (cardHeight + 0.5);

      slide.addShape('roundRect', {
        x: x, y: y, w: cardWidth, h: cardHeight,
        fill: { color: cleanColor(this.palette.primary, '1E2761') },
        rectRadius: 0.08
      });
      slide.addShape('ellipse', {
        x: x + cardWidth - 1.0, y: y - 0.6, w: 1.8, h: 1.8,
        fill: { color: cleanColor(this.palette.accent, 'FFFFFF'), transparency: 88 }
      });
      slide.addText(item.value || '', {
        x: x + 0.35, y: y + 0.42, w: cardWidth - 0.7, h: 1.0,
        fontSize: 42, fontFace: FONT, color: 'FFFFFF', bold: true,
        align: 'left', valign: 'middle', fit: 'shrink'
      });
      slide.addText(item.label || '', {
        x: x + 0.35, y: y + 1.5, w: cardWidth - 0.7, h: 0.5,
        fontSize: 14, fontFace: FONT, color: cleanColor(this.palette.secondary, 'CADCFC'),
        align: 'left', valign: 'middle', fit: 'shrink'
      });
    });
  }

  addSummaryPage(page) {
    const slide = this.pptx.addSlide();
    slide.background = { color: cleanColor(this.palette.primary, '1E2761') };

    slide.addText(page.title || (this.language === 'zh' ? '总结' : 'Summary'), {
      x: 0.8, y: 0.5, w: 11.7, h: 1.0,
      fontSize: 34, fontFace: FONT, color: 'FFFFFF', bold: true, valign: 'middle'
    });

    const points = asArray(page.items).slice(0, 6);
    const startY = 1.9;
    const itemHeight = Math.min(0.95, (points.length > 0 ? 4.6 / points.length : 0.9));
    points.forEach((point, index) => {
      const label = typeof point === 'string' ? point : (point.title || point.body || point.label || '');
      const y = startY + index * itemHeight;
      slide.addShape('ellipse', {
        x: 0.9, y: y + 0.12, w: 0.4, h: 0.4,
        fill: { color: cleanColor(this.palette.accent, 'FFFFFF') }
      });
      slide.addText(String(index + 1), {
        x: 0.9, y: y + 0.12, w: 0.4, h: 0.4,
        fontSize: 13, fontFace: FONT, color: cleanColor(this.palette.primary, '1E2761'),
        bold: true, align: 'center', valign: 'middle'
      });
      slide.addText(label, {
        x: 1.55, y: y, w: 10.8, h: itemHeight - 0.12,
        fontSize: 17, fontFace: FONT, color: 'FFFFFF',
        valign: 'middle', fit: 'shrink'
      });
    });
  }

  addEndPage(page) {
    const slide = this.pptx.addSlide();
    slide.background = { color: cleanColor(this.palette.primary, '1E2761') };

    slide.addShape('ellipse', {
      x: 4.1, y: 1.35, w: 5.1, h: 5.1,
      fill: { color: cleanColor(this.palette.accent, 'FFFFFF'), transparency: 92 }
    });
    slide.addText(page.message || (this.language === 'zh' ? '谢谢观看' : 'Thank You'), {
      x: 0.5, y: 2.9, w: 12.3, h: 1.5,
      fontSize: 44, fontFace: FONT, color: 'FFFFFF', bold: true,
      align: 'center', valign: 'middle', fit: 'shrink'
    });
    if (page.subtitle) {
      slide.addText(page.subtitle, {
        x: 1.5, y: 4.55, w: 10.3, h: 0.7,
        fontSize: 16, fontFace: FONT, color: cleanColor(this.palette.secondary, 'CADCFC'),
        align: 'center', valign: 'middle', fit: 'shrink'
      });
    }
  }

  generate(data) {
    const outline = asArray(data.outline);
    const pages = outline.length > 0 ? outline : defaultOutline(data.title || this.pptx.title);
    pages.forEach((page) => {
      const type = String(page.type || 'content').toLowerCase();
      switch (type) {
        case 'cover':
          this.addCoverPage({ ...page, title: page.title || data.title || this.pptx.title, subtitle: page.subtitle || data.subtitle || '' });
          break;
        case 'toc':
          this.addTocPage(page);
          break;
        case 'big-number':
          this.addBigNumberPage(page);
          break;
        case 'summary':
          this.addSummaryPage(page);
          break;
        case 'end':
          this.addEndPage(page);
          break;
        default:
          this.addContentPage(page);
          break;
      }
    });
  }

  async save(outputPath) {
    const absolute = path.resolve(outputPath);
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    await this.pptx.writeFile({ fileName: absolute });
    return absolute;
  }
}

function defaultOutline(title) {
  return [
    { type: 'cover', title: title, subtitle: '由青 AI 智能生成' },
    { type: 'toc', title: '目录', items: ['内容概览', '核心要点', '案例分析', '数据解读', '总结与展望'] },
    { type: 'content', title: '内容概览', items: ['这是第一个核心要点，说明主题背景', '这是第二个核心要点，说明关键结论', '这是第三个核心要点，说明下一步行动'] },
    { type: 'content', title: '核心要点分析', items: ['要点一：重要发现', '要点二：关键数据', '要点三：落地建议'] },
    { type: 'big-number', title: '核心数据', numbers: [{ value: '10', label: '页演示' }, { value: '3+', label: '核心要点' }, { value: '100%', label: '重点覆盖' }] },
    { type: 'summary', title: '总结', items: ['结论一：问题清晰', '结论二：方案可行', '结论三：行动明确'] },
    { type: 'end', message: '谢谢观看' }
  ];
}

function parseArgs(argv) {
  const options = { input: '', output: '', title: '', palette: '', language: 'zh', author: 'Qing' };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const next = () => argv[++i];
    if (arg === '--input' || arg === '-i') options.input = next() || '';
    else if (arg === '--output' || arg === '-o') options.output = next() || '';
    else if (arg === '--title' || arg === '-t') options.title = next() || '';
    else if (arg === '--palette' || arg === '-p') options.palette = next() || '';
    else if (arg === '--lang' || arg === '-l') options.language = next() || '';
    else if (arg === '--author' || arg === '-a') options.author = next() || '';
  }
  return options;
}

function sanitizeFileName(value) {
  return String(value || '演示文稿')
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim();
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (!options.input) {
    console.error('Usage: node ppt-generator.js --input outline.json [--output out.pptx] [--title T] [--palette P] [--lang zh|en]');
    process.exit(2);
  }
  if (!fs.existsSync(options.input)) {
    console.error('Input file not found: ' + options.input);
    process.exit(2);
  }
  const data = JSON.parse(fs.readFileSync(options.input, 'utf8'));
  const title = data.title || options.title || '演示文稿';
  const generator = new QingPptGenerator({
    title: title,
    language: data.language || options.language,
    palette: data.palette || options.palette || 'midnight-executive',
    author: data.author || options.author
  });
  generator.generate(data);
  const output = options.output || path.join(process.cwd(), sanitizeFileName(title) + '.pptx');
  generator.save(output)
    .then((savedPath) => console.log('PPT saved: ' + savedPath))
    .catch((error) => {
      console.error('Generation failed: ' + (error && error.message ? error.message : String(error)));
      process.exit(1);
    });
}

main();