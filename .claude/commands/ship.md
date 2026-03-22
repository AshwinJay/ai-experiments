Run tests, verify docs are in sync with code, then commit, push, open a PR, merge it, and delete the branch.

Steps:
1. Run `mvn test` — fix any failures before continuing.
2. Read README.md, PLAN.md, and CLAUDE.md and check them against recent code changes; fix anything stale.
3. Show the user a summary of all changes (files modified, test results, any doc fixes made) and ask for confirmation before proceeding. Stop here if they say no.
4. Commit all changes with a descriptive message on a new branch.
5. Push the branch and show the user the diff/commit summary, then ask for confirmation before opening the PR. Stop here if they say no.
6. Open a PR with a summary and test plan, merge (squash), delete the remote and local branch.
7. Pull main to sync local.
