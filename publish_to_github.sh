#!/bin/bash
# Script to push Voice Recorder Vault project to GitHub

if [ -z "$1" ]; then
    echo "Usage: ./publish_to_github.sh <github_repository_url>"
    echo "Example: ./publish_to_github.sh https://github.com/username/voice-recorder-vault.git"
    exit 1
fi

REPO_URL=$1
echo "Setting up remote origin to $REPO_URL..."
git remote remove origin 2>/dev/null || true
git remote add origin "$REPO_URL"
git branch -M main

echo "Pushing code to GitHub..."
git push -u origin main

echo ""
echo "Done! To enable the Privacy Policy website:"
echo "1. Go to repository Settings -> Pages"
echo "2. Under 'Build and deployment', choose Source: 'Deploy from a branch'"
echo "3. Select branch: 'main' and folder: '/docs'"
echo "4. Save. Your Privacy Policy will be live!"
