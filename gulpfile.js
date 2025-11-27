'use strict';

/**
 * Gulpfile that loads tasks from the script directory
 */

import gulp from 'gulp';
import mkdocsTasks from './scripts/mkdocs.js';
import journalTasks from './scripts/journal.js';

// Load gulp tasks from script modules
mkdocsTasks(gulp);
journalTasks(gulp);

export const dev = gulp.series('mkdocs:serve', 'mkdocs:open');

// Export gulp to make it available to the gulp CLI
export default gulp;
