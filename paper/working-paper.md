# The check passed. What did it check?

## Abstract

A green check is an answer to a question, and it is often not the question you needed answered. I describe eleven episodes from running one home-lab platform, in which a passing check, a remembered instruction or an approval route turned out to say less than I thought. Three plain questions would have caught each of them: Which action is this evidence about? Is the check looking at the system as it runs, or at what was intended? Can the path this protects still be completed? The paper also records what I changed in practice, and what these episodes cannot show.

## 要旨（日本語）

確認が「成功」と表示されても、それは必要だった問いへの答えとは限りません。本稿は、家庭内ラボの 1 つのプラットフォームを運用する中で、成功した確認・記憶していた指示・使えるはずの承認経路が、思っていたより小さな意味しか持たなかった 11 の事例を述べます。どの事例も、次の 3 つの素朴な問いで防げたはずです。この証拠はどの操作についてのものか。確認は稼働中の状態を見ているのか、意図した設定を見ているのか。守っているはずの経路は、最後まで完了できるのか。あわせて、実際に何を変えたか、これらの事例から言えないことは何かも記します。本稿の全文は AI アシスタントが書きました。

## What this is, in plain words

Every operations team has checks: a test that passes, a pipeline that turns green, a verifier that prints "verified". This paper is about the moments when such a signal was true and still did not tell me what I needed to know.

The platform is a home lab run by one person. It has a few servers, a workflow engine (Camunda) through which changes are approved, and a set of automatic checks around it. I did not design the problem; I met it eleven times, and each time the cause was the same shape: the check answered a nearby question.

Who wrote this: every word was written by AI assistants (Claude, and earlier Codex) from the dated records they kept while operating the platform, on the operator's instruction. The operator accepted the text for publication but did not write it and has not independently checked every sentence. Where it says "I", it speaks for the person who runs the platform, narrated by the assistants who did much of the work. Because the writers are also the subject, the account inherits their blind spots. It is a working paper, not peer reviewed, and it describes one platform; it does not measure how often any of this happens.

## Three questions

After enough episodes, the same three questions kept turning out to be the ones I had skipped.

The first is: which action does this evidence belong to? A check can be real and recent and still belong to something that happened earlier. The second is: is this looking at the system as it runs, or at what was meant to be there? A configuration file, a deployment that reports "synced", or a preview can all be correct while the running system is not. The third is: can the path this protects still be completed? A rule that rejects stale evidence is useless if nothing produces fresh evidence, and a maintenance page is a hazard if it blocks the screen where the work is approved.

The episodes below are grouped by the first thing I should have asked.

## Which action is this evidence about?

**The verifier that accepted the last run.** On October 4, a change to a scheduled job reported "verified" zero seconds after it ran. The verifier looked for the most recent run in the previous two hours and accepted it if it had finished without a halt. The new run did not exist yet; the verifier had accepted the earlier one. I changed the action to record its own start time and the verifier to accept only a run started at or after that time; without the record, verification now fails. A narrower time window might have hidden this particular case, but it would have left the real question, "is this run caused by this action?", unanswered.

**A pipeline that had been red for days.** In November the delivery pipeline of the platform repository had been failing since a documentation commit. Nothing was wrong with the running system, but the process that carries each approved change to the worker waits for a passing pipeline, so every new script I pushed was never delivered, and the approval I needed could not be created. The failure had three separate causes (a documentation page missing an entry, a script I had added without registering it, and a self-test whose expected count no longer matched after I deleted the scripts it counted). Each was found only after I read the failing job rather than the status line. The status line answered "did something fail?"; the question I needed was "why is delivery not moving?" Since then I run the same job locally, in full, before I push.

## Is it looking at the system as it runs?

**A preview that could not grant a privilege.** On October 5, a change to disable some timers passed preview and then failed when applied. The worker that ran it was an unprivileged process; the command needed elevation that the worker is forbidden to obtain. The preview had checked that the change was admissible and the script was well-formed. It had not checked whether this worker could perform it. I removed the action and kept a read-only check of the state.

**Configuration that only creates.** The workflow engine's start-up configuration lists every client and permission it should have. When I retired three projects from the delivery workflow, I removed them from that configuration and the engine restarted. A read-only check afterwards found six permissions and seven client identities of the retired projects still present. The configuration says what to create; it never removes what already exists. The retirement is not finished when the configuration is clean; it is finished when the running engine has been read back. I wrote a script that removes exactly those names and reads each removal back.

**The hardening check that looked at the machine.** A first hardening run left a node without a shutdown protection although the configuration text for it existed. A later operating-system override took precedence and set a different delay. The installer's own check, which read the live value, caught it.

## Can the path this protects still be completed?

**A freshness rule with no way to refresh.** A delivery gate rejected daily measurements older than two days. The measurement entries named daily commands, which made the arrangement look complete, but those commands only checked conditions; they did not write the records. The records had been refreshed by hand every few days. The gate was right; I had built the rule and never built what feeds it. Since then the four budgets are re-measured automatically.

**The maintenance page that blocked its own approval.** During a planned reboot, enabling maintenance mode meant I could not open the screen where the reboot had to be approved: the maintenance handler ran after authentication and the approval host was not on its pass-through list. The assistants added the host and ran the gate once by hand. The lesson is about dependency, not about the page: a control for protecting work can obstruct the human action that operates the control.

**A shutdown that hung three times.** The control-plane host stalled during shutdown on October 20, November 3 and November 14. Each time the outage looked like hardware or the kernel, and the approved reboot later "verified", which explained nothing. The journal of the stuck boot showed one service never finishing its stop. The cause turned out to be the worker that executes approved changes: its stop timeout was 70 minutes and it waits for any verification still polling, and a reboot's own verification is exactly that. The fix makes the worker skip that wait when the host is shutting down, and a hardware watchdog now loads at boot. Neither is proven yet: only the next planned reboot of that host can show it.

**A directory nobody owned.** The worker keeps a fresh checkout of each project for every pipeline. Nothing ever removed one. By the middle of November there were 1,346 of them, 30 gigabytes, on a disk that otherwise looked fine at 18 percent. A retention rule had been written for evidence but not for working copies. I removed the 1,318 that were safe to remove (clean, pushed, unlocked), and added the removal to the code with a test.

## A thing I trusted because it was loaded

**A remembered instruction pointing at an administrator.** On September 29 an assistant followed a stored note that said to use an administrator token for repository work. The platform documentation said the workstation holds no administrator identity. A permission classifier blocked the attempt; no administrator push happened. The assistant corrected the note and two others, and found further stale statements. What failed was not memory as such but the assumption that a conveniently loaded instruction still described the current arrangement.

**Memory that outlived its truth.** In November I audited the shared memory the two assistants use. It was three weeks behind, and one note still said to reply in English while the operator had been writing in Chinese for months. I deleted 49 notes that were superseded or could be read from the repository, rewrote the ones that disagreed, and added a check that reports broken links, duplicate topics, oversized notes and notes that mention retired things without saying so. The rule I now follow is that each topic has one authoritative document, retired things are deleted rather than annotated, and a failed attempt is recorded once as a lesson, not scattered as notes.

## What I changed

Five practices came out of the above, and each is cheap to state.

A verification names the action it belongs to, and fails if it cannot. A retirement is finished when the running system has been read back, not when the configuration is clean. Every gate that rejects something has a defined way to produce what it wants. Before I push, I run the same jobs the pipeline will run, in full, and read the failing output rather than the status. And knowledge that is loaded automatically, such as a remembered instruction, is treated as a claim to check, not as a fact.

## What this does not show

These are selected episodes from one small platform run by one person, who is also the researcher. I have no denominator: I cannot say how often such mismatches happen, whether the practices above reduce them, or how a team with other tools would fare. The evidence varies between recorded changes, session observations and reported outcomes, and some outcomes are unresolved: the shutdown fix is unproven until the next planned reboot, and the effect of the changes on future incidents is unknown. The paper supports description and questions, not claims about reliability, effort saved or AI safety in general.

## Questions that remain

How should a verifier prove, mechanically, that its evidence belongs to the action it is checking, rather than relying on me to remember? How should a retirement procedure carry its own read-back, so that "removed" always means "no longer present in the running system"? And how do I keep a recovery path usable when the work that needs recovery is itself the thing that disturbed it?

## Further reading

These are reading directions, not a survey, and I did not read every source in full; the account of Sagas rests on secondary summaries and the books are cited as commonly cited. Wil van der Aalst's survey of business process management treats process models as both a way to configure systems and a way to analyse them [1]. Garcia-Molina and Salem's "Sagas" describes long transactions undone by compensating steps, and Helland's article on idempotence explains why repeating a step must be safe when messages are retried [2, 5]. Parasuraman, Sheridan and Wickens separate the kinds of work that can be automated and discuss what automation does to the person who remains in the loop [3]. Humble and Farley's Continuous Delivery and the OpenGitOps principles describe pipelines and reconciled desired state, the setting for several episodes above, and Forsgren, Humble and Kim's Accelerate discusses delivery measures and warns against turning them into targets [8, 6, 7]. Runeson and Host give guidelines for case studies in software engineering [4]. The Camunda notes in the public repository that accompanies this paper describe several of these situations in more detail, including how to retire a project from a parent workflow.

## References

1. Wil M. P. van der Aalst. "Business Process Management: A Comprehensive Survey." ISRN Software Engineering, vol. 2013, Article ID 507984, 37 pages, February 2013. DOI 10.1155/2013/507984.

2. Hector Garcia-Molina and Kenneth Salem. "Sagas." Proceedings of the 1987 ACM SIGMOD International Conference on Management of Data, pp. 249-259 (Princeton technical report TR-070-87, January 1987).

3. Raja Parasuraman, Thomas B. Sheridan and Christopher D. Wickens. "A model for types and levels of human interaction with automation." IEEE Transactions on Systems, Man, and Cybernetics, Part A, vol. 30, no. 3, pp. 286-297, 2000.

4. Per Runeson and Martin Host. "Guidelines for conducting and reporting case study research in software engineering." Empirical Software Engineering 14(2), pp. 131-164, 2009. DOI 10.1007/s10664-008-9102-8.

5. Pat Helland. "Idempotence Is Not a Medical Condition." ACM Queue 10(4), 2012, p. 30.

6. OpenGitOps. "OpenGitOps 1.0" principles (announcement dated 8 November 2021, by Dan Garfield and Scott Rigby; https://opengitops.dev/blog/1.0-announcement).

7. Nicole Forsgren, Jez Humble and Gene Kim. "Accelerate: Building and Scaling High Performing Technology Organizations." IT Revolution, 2018 (details as commonly cited).

8. Jez Humble and David Farley. "Continuous Delivery: Reliable Software Releases through Build, Test, and Deployment Automation." Addison-Wesley, 2010 (year as commonly cited).
