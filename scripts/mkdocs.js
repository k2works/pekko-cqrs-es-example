'use strict';

import {execSync} from 'child_process';
import fs from 'fs';
import path from 'path';

/**
 * @type {boolean} Windows環境かどうかをチェック
 */
const isWindows = process.platform === 'win32';

// Function to register the mkdocs:serve task
export default function (gulp) {
    // Helper function to remove site directory
    const removeSiteDirectory = () => {
        const siteDir = path.join(process.cwd(), 'site');
        if (fs.existsSync(siteDir)) {
            console.log('Removing existing site directory...');
            fs.rmSync(siteDir, {recursive: true, force: true});
            console.log('Site directory removed successfully!');
        }
    };

    // MkDocs serve task
    gulp.task('mkdocs:serve', (done) => {
        try {
            console.log('Starting MkDocs server using Docker Compose...');

            // Execute docker-compose up command to start mkdocs service
            execSync('docker compose up -d mkdocs', {stdio: 'inherit'});

            console.log('\nMkDocs server started successfully!');
            console.log('Documentation is now available at http://localhost:8000');
            console.log('Press Ctrl+C to stop the server when done.');

            done();
        } catch (error) {
            console.error('Error starting MkDocs server:', error.message);
            done(error);
        }
    });

    // MkDocs build task
    gulp.task('mkdocs:build', (done) => {
        try {
            console.log('Building MkDocs documentation...');

            // Remove existing site directory before building
            removeSiteDirectory();

            // Execute docker-compose run command to build mkdocs documentation
            execSync('docker compose run --rm mkdocs mkdocs build', {stdio: 'inherit'});

            console.log('\nMkDocs documentation built successfully!');

            done();
        } catch (error) {
            console.error('Error building MkDocs documentation:', error.message);
            done(error);
        }
    });

    // MkDocs stop task
    gulp.task('mkdocs:stop', (done) => {
        try {
            console.log('Stopping MkDocs server...');

            // Execute docker-compose down command to stop mkdocs service
            execSync('docker compose down', {stdio: 'inherit'});

            console.log('MkDocs server stopped successfully!');

            done();
        } catch (error) {
            console.error('Error stopping MkDocs server:', error.message);
            done(error);
        }
    });

    // MkDocs open task
    gulp.task('mkdocs:open', (done) => {
        try {
            console.log('Opening MkDocs server...');

            const command = isWindows ? 'start http://localhost:8000' : 'open http://localhost:8000';
            execSync(command, {stdio: 'inherit'});

            console.log('MkDocs server opened successfully!');

            done();
        } catch (error) {
            console.error('Error opening MkDocs server:', error.message);
            done(error);
        }
    });
}