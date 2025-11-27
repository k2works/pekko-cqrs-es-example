'use strict';

import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

// Increase buffer to safely handle large diffs/logs
const MAX_BUFFER = 256 * 1024 * 1024; // 256 MB

// Function to register the journal:generate task
export default function(gulp) {
  // Helper function to generate journal for a specific date
  const generateJournalForDate = (dateStr, journalDir) => {
    // Format date as YYYYMMDD
    const dateObj = new Date(dateStr);
    const formattedDate = dateObj.toISOString().slice(0, 10).replace(/-/g, '');

    // File path for this date's journal
    const journalFile = path.join(journalDir, `${formattedDate}.md`);

    // Get commits for this date
    const commits = execSync(`git log --since="${dateStr} 00:00:00" --until="${dateStr} 23:59:59" --format="%h %s%n%b"`, { maxBuffer: MAX_BUFFER }).toString();

    // Skip if no commits
    if (!commits.trim()) {
      console.log(`No commits found for ${dateStr}`);
      return false;
    }

    // Get detailed changes for each commit on this date
    const commitHashesOutput = execSync(`git log --since="${dateStr} 00:00:00" --until="${dateStr} 23:59:59" --format="%h"`, { maxBuffer: MAX_BUFFER }).toString();
    const commitHashes = commitHashesOutput.split('\n').map(line => line.trim()).filter(Boolean);

    const detailedCommits = [];

    commitHashes.forEach(hash => {
      // Get commit details
      const commitMessage = execSync(`git show -s --format="%s%n%b" ${hash}`, { maxBuffer: MAX_BUFFER }).toString().trim();

      // Get files changed
      const filesChangedOutput = execSync(`git show --name-status ${hash}`, { maxBuffer: MAX_BUFFER }).toString();
      const filesChanged = filesChangedOutput.split('\n')
          .map(line => line.trim())
          .filter(line => /^[AMDRT]\s/.test(line));

      // Get code changes (diff) with robust handling for large diffs
      let diff = '';
      try {
        diff = execSync(`git show ${hash} --color=never`, { maxBuffer: MAX_BUFFER }).toString();
      } catch (e) {
        // Fallback to a summary if diff is too large or any error occurs
        const summary = execSync(`git show --stat --oneline ${hash} --color=never`, { maxBuffer: MAX_BUFFER }).toString();
        diff = `[Diff too large or failed to load. Showing summary instead.]\n\n${summary}`;
      }

      detailedCommits.push({
        hash,
        message: commitMessage,
        filesChanged,
        diff
      });
    });

    // Create journal content
    let content = `# 作業履歴 ${dateStr}\n\n`;
    content += `## 概要\n\n`;
    content += `${dateStr}の作業内容をまとめています。\n\n`;

    // Add each commit
    detailedCommits.forEach(commit => {
      content += `## コミット: ${commit.hash}\n\n`;
      content += `### メッセージ\n\n`;
      content += `\`\`\`\n${commit.message}\n\`\`\`\n\n`;

      content += `### 変更されたファイル\n\n`;
      commit.filesChanged.forEach(file => {
        content += `- ${file}\n`;
      });
      content += `\n`;

      content += `### 変更内容\n\n`;
      content += `\`\`\`diff\n${commit.diff}\n\`\`\`\n\n`;

      // Add PlantUML diagram placeholder if there are significant structural changes
      const hasStructuralChanges = commit.filesChanged.some(f =>
          f.endsWith('.rb') &&
          (f.includes('model') || f.includes('controller') || f.includes('service'))
      );

      if (hasStructuralChanges) {
        content += `### 構造変更\n\n`;
        content += `\`\`\`plantuml\n@startuml\n`;
        content += `' このコミットによる構造変更を表すダイアグラムをここに追加してください\n`;
        content += `' 例:\n`;
        content += `' class NewClass\n`;
        content += `' class ExistingClass\n`;
        content += `' NewClass --> ExistingClass\n`;
        content += `@enduml\n\`\`\`\n\n`;
      }
    });

    // Write to file
    fs.writeFileSync(journalFile, content);
    console.log(`Created journal entry for ${dateStr} at ${journalFile}`);
    return true;
  };

  // Journal generation task for a specific date
  gulp.task('journal:generate:date', (done) => {
    // Get date from command line arguments
    const args = process.argv.slice(3);
    const dateArg = args.find(arg => arg.startsWith('--date='));

    if (!dateArg) {
      console.error('Error: Please provide a date using --date=YYYY-MM-DD format');
      done();
      return;
    }

    const dateStr = dateArg.split('=')[1];

    // Validate date format
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
      console.error('Error: Date must be in YYYY-MM-DD format');
      done();
      return;
    }

    // Directory to store journal entries
    const journalDir = path.join('docs', 'journal');

    // Create directory if it doesn't exist
    if (!fs.existsSync(journalDir)) {
      fs.mkdirSync(journalDir, { recursive: true });
    }

    const result = generateJournalForDate(dateStr, journalDir);

    if (!result) {
      console.log(`No journal entry was created for ${dateStr}`);
    }

    done();
  });

  // Journal generation task for all dates
  gulp.task('journal:generate', (done) => {
    // Directory to store journal entries
    const journalDir = path.join('docs', 'journal');

    // Create directory if it doesn't exist
    if (!fs.existsSync(journalDir)) {
      fs.mkdirSync(journalDir, { recursive: true });
    }

    // Get all commit dates
    const datesOutput = execSync('git log --format=%ad --date=short', { maxBuffer: MAX_BUFFER }).toString();
    const dates = [...new Set(datesOutput.split('\n').map(line => line.trim()).filter(Boolean))];

    dates.forEach(dateStr => {
      // Format date as YYYYMMDD
      const dateObj = new Date(dateStr);
      const formattedDate = dateObj.toISOString().slice(0, 10).replace(/-/g, '');

      // File path for this date's journal
      const journalFile = path.join(journalDir, `${formattedDate}.md`);

      // Skip if file already exists
      if (fs.existsSync(journalFile)) {
        return;
      }

      // Generate journal for this date
      generateJournalForDate(dateStr, journalDir);
    });

    console.log("Journal generation completed.");
    done();
  });
}
