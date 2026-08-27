# Intentional defect patches

These patches create benchmark branches with known financial-reliability defects. Never apply them to the main branch, a production system, or a repository containing real financial data.

The create-defect-branches script applies each patch to a separate benchmark branch after the verified main commit exists. Every defect commit is labeled INTENTIONAL BENCHMARK DEFECT.

The patches are public so the repository remains auditable. A controlled evaluation gives an agent only the selected branch and task prompt; hidden test implementations remain in a separate private grader.

