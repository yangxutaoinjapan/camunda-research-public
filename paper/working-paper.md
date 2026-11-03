# When my operational checks answered the wrong question

## Abstract

I describe episodes from operating a home-lab platform in which a successful check, a remembered instruction, or an available approval route meant less than I initially assumed. The episodes move from verification against an earlier run and failures at delivery gates to recovery dependencies, mistaken routing beliefs, and a maintenance and reboot sequence with unresolved outcomes. They leave me with questions about how to connect evidence to a particular action, preserve access to recovery, and check the running system rather than its intended configuration.

## 要旨（日本語）

本稿は、家庭内ラボのプラットフォームを運用する中で、成功した確認、記憶していた指示、利用できる承認経路が、当初想定していたよりも小さな意味しか持たなかった事例を記述する。事例は、以前の実行に対する検証や配信ゲートでの失敗から、復旧への依存、誤ったルーティングの認識、結果が未解明のままのメンテナンスと再起動の一連の出来事へと進む。そこから、証拠を特定の操作にどう結びつけるか、復旧への経路をどう保つか、意図した設定ではなく稼働中のシステムそのものをどう確認するか、という問いが残る。本稿の全文は AI アシスタント（Claude と Codex）が書いたものである。

## What this is

This is an experience report about one platform: a home lab operated by one person. It is a working paper, not peer reviewed, and its purpose is descriptive.

**Every word of it was written by AI assistants (Claude and Codex).** They summarised their own working practice on this platform, on the operator's instructions, from the dated records they kept while building and operating it. The operator accepted it for publication; the operator did not write the text and has not independently checked each statement. Figures were cross-checked against the underlying records, and nothing in it is a measurement the records do not contain. Where the text says "I", it speaks for the operator of the platform, narrated by the AI assistants that did much of the work.

Because the writer and the subject are the same assistants, the account inherits their blind spots. It reports what the records show, including what they do not.

## The setting

I run a home-lab platform as one person. Operational changes go through a workflow engine, Camunda, with human approval, and verification runs after each change. Around that path are version-controlled configuration, CI checks, execution workers, network and identity services, and checks that read back the resulting state.

Those pieces do not all answer the same question. Approval concerns permission to proceed; an execution worker has its own privilege boundary; a delivery check concerns what reached it; and a verifier needs evidence of the particular action. The episodes below describe occasions when I treated those boundaries too loosely, or found that the path through them could not complete.

## A successful run that belonged to an earlier action

On October 4, a runner change appeared to verify immediately. The verifier looked for the latest ingestion run in the last two hours and accepted it if it had finished without a halt. The change reported verified 0 s after its action.

At first, the result looked like the ordinary conclusion of the change: the action had run and the check had passed. But the new job's run row did not exist yet. The verifier had accepted the previous run. Reading the run identifier exposed a mismatch that the check itself had not detected.

The problem was more specific than old data in general. The earlier run was recent enough for the verifier's window and satisfactory by its completion rule. It still could not show that this action had worked. The question I needed answered was whether the run started by this action completed successfully. The check instead answered whether a recent run had done so.

I changed the action to write its start time and the verifier to accept only a run started at or after that time. Without that record, verification fails closed. That makes the intended relationship explicit, but this occurrence alone does not establish how reliably the revised check behaves. A narrower polling window might also have avoided this particular acceptance much of the time; it would still leave the relationship between the action and its evidence implicit.

On November 3, a related omission appeared during preparation for a tunnel-service upgrade. A new verifier lacked the approved-action freshness marker. This time, a CI self-test failed, and the correction added freshness enforcement like the other verifiers. I have no passing rerun receipt for that correction here. Nor does this later episode show stale evidence accepted at runtime: it records a missing requirement caught by CI. The distinction matters to me because the earlier mismatch was found by reading a run identifier after an apparent success, whereas the later omission was caught before that kind of runtime outcome was established.

## A failed gate followed by a successful shell command

Also on October 4, my assistant checked the latest pipeline before pushing. The checking script returned failure when the pipeline failed. The assistant piped its output through a command that retained only the last line, then chained the rebase and push to the apparent success of that pipeline of shell commands.

I initially had what looked like the necessary control: a pipeline check before a push, backed by a remembered instruction against piping a gate. The check even printed that the pipeline had failed. But the final output-filtering command exited successfully, and its status allowed the following commands to run. Two commits were pushed while the main branch was red.

The CI failures had concrete causes of their own. A new check imported a YAML library absent from the job image. The attempted fix then failed another job because a new script's self-test had not been wired into CI. Only after both affected jobs were run whole under a shell that stopped on error did the next pipeline pass.

Meanwhile, the execution worker's checkout remained behind. A starter refused an approved change because its runbook differed from the delivered checkout. That refusal was a separate boundary that held even though the push-time check had been defeated by shell composition. I could not treat “a check ran” as equivalent to “its failure controlled the next action.”

The immediate corrections addressed the affected jobs, and the next pipeline passed. What I do not have is evidence that the remembered rule became a reliable push-time control. The account concerns one session and a rule already repeated in memory. Shell-composition carelessness is a sufficient explanation for this occurrence; it does not establish a fault inherent in the workflow design.

## A freshness rule with no working refresh path

On October 5, a different delivery gate stopped work rather than letting it through. The CI rule allowed a two-day maximum age for daily budget measurements. Measurements for route ownership, secret projection, probe coverage, and sizing still carried October 2 dates. The contemporaneous account says every push failed that job, including an approved change and a runbook correction unrelated to refreshing those measurements.

The measurement entries named daily commands, which made the maintenance arrangement look more complete than it was. I could read a daily check beside a daily measurement and assume the two were connected. They were not: those commands checked conditions but did not write the measurement records. The records had instead been refreshed by hand every three to five days.

The gate was enforcing its written age rule. The failure was in my arrangement for supplying it with current evidence. That distinction changes where I would look for a repair. Relaxing the checker would address the interruption by changing the requirement; refreshing the records would meet the existing requirement. The recorded change re-measured the four budgets live on October 5.

That update is the concrete change I can describe. It is not evidence that subsequent refreshes became automatic or that unrelated delivery could no longer be blocked this way. I also have no independently collected pipeline count behind the account's description of every push failing. The episode leaves a practical question: when I require fresh evidence, have I built the path that produces it, or merely the rule that rejects its absence?

## A preview that could not grant runtime privilege

Another October 5 change concerned disabling host backup timers. The host worker ran as an unprivileged user with a no-new-privileges restriction. Privileged host operations belonged to a separate root-owned unit. My assistant nevertheless wrote an action runbook that tried to disable the timers through non-interactive privilege elevation.

The approved change passed preview and failed when applied. The privilege-elevation command reported the no-new-privileges restriction. At first, a passed preview had looked like sufficient reason to proceed through approval to execution. What it had actually established was admission and script syntax, not the runtime authority of the selected worker.

The boundary was already part of the worker's execution contract. Neither a syntactically valid command nor approval of the change could make that worker acquire the privilege the command required. A different privileged runner could have performed the operation; this worker could not perform it in the proposed way.

I removed the action runbook and retained the read-back verifier. That was a narrower outcome than completing the intended timer change. It removed an incompatible execution path while keeping a way to inspect the relevant state.

The apply failure is recorded in the task account and change history; the workflow outcome record was not available for independent collection in that account. I therefore cannot turn the removal into a successful timer operation. What remains unresolved is how preview should check the privileges an action needs against the worker that will actually execute it. Syntax and admission were real checks, but they answered different questions.

## Recovery needed the login path I had disturbed

On October 9, after rebuilding Camunda, its Hub login page kept spinning. The contemporaneous explanation was that an access-control layer blocked background browser requests to the identity issuer. My assistant tried to work around that inside the platform by adding a gateway listener, extending certificate coverage, allowing the name at the certificate authority, and restarting certificate-authority workloads when an edit was not picked up. Hub still spun.

I chose to restore the original state and configure the access-control layer myself. At first, reverting the changes and seeing the deployment controller report synchronization seemed a plausible route back. Yet the listener remained live. With the server-side apply behavior in use, removing that field from version control had not removed it from the running gateway. A pruning runbook existed for this case.

That runbook required an approved change, but I could no longer open Camunda's task interface through either available address. On the device used for Hub, I had followed the assistant's instruction to add a local host override. The same issuer host was used by the task interface. The override sent its login traffic to the internal gateway too. Removing the line restored access, according to my account; the assistant did not verify name resolution on that device.

I explicitly authorized an exception, and the assistant ran the pruning script by hand outside the workflow. The read-back showed 25 listeners, matching version control. The certificate was ready with its original names, and both certificate-authority workloads were running. A deployment comparison error was also present and dated from before the revert. There was no workflow outcome record for the manual execution.

The recovery was therefore more complicated than a successful revert. I had introduced a client-side workaround that affected a shared login dependency, and the normal cleanup path needed that login. It remains possible that the manual exception would have been unnecessary had removing the host override been suggested first. The original Hub symptom was not shown to be fixed or unfixed by access-control changes, because I had not yet configured them. I cannot use the recovery account to settle that earlier explanation.

## A remembered instruction pointed to an administrator identity

On September 29, during creation and mirroring of a repository, my assistant followed memory loaded at session start. It said to use an administrator token for repository work and bypass the workstation credential store for each command. The platform documentation instead assigned workstation pushes to a non-administrator writer and said the workstation held no administrator identity.

The first belief was that the remembered instruction described the established way to do the job. The assistant wrote a script to obtain the administrator token, create the project, and push with it. A permission classifier blocked execution. This was a blocked attempt, not an observed administrator push.

After I questioned which identity the other repositories used, the assistant read the platform documentation and their remote configuration. It confirmed the writer identity and corrected both the local memory and three shared-memory files. It also found other stale statements: one described a template repository as public even though an anonymous API request returned 404, and another described a retired pull-secret rule.

Those corrections changed the remembered instructions. They do not establish that memory as a mechanism was the cause. The administrator-token instruction had originally been written as my instruction and was followed as written; its wording could explain the problem. What failed in this occurrence was the assumption that a conveniently loaded instruction still represented the governing arrangement.

I do not know how often later sessions encounter such contradictions. The control that stopped this attempt was the permission classifier, not a platform governance gate. That is important when I assess what the episode says about my system: the attempted action was caught, but by a different boundary from the one I might otherwise have credited.

## The route I assumed was not the route every request took

By October 23, I was working with an overly broad picture of the public Camunda routes. I had assumed that all of those hosts passed through the same remote gateway. The routing account instead described several interfaces already redirecting to their serving hosts, with only the orchestration root proxied through the gateway.

At first, the shared description of these interfaces encouraged me to reason about a shared network path. Route-level inspection corrected that attribution. A name for a group of services was not enough to establish which request crossed which boundary.

The change history records direct serving for the task and operations interfaces and the correction to the routing description. This gave me a more specific account of the paths involved. It did not establish a network-performance explanation. The timing values supplied with the account had no raw traces or browser capture and did not isolate gateway processing from other network or browser effects.

I have therefore left the timing comparison out of this report. The useful change here is the correction of my routing belief, not a claim that a particular route caused an observed delay. I still cannot say what share of the reported timing belonged to the gateway, the network, or the browser. This episode sits beside the memory episode because both required replacing a broad, convenient description with a narrower account of the boundary actually involved.

## The maintenance page blocked its own approval entrance

During the maintenance window associated with the control-plane reboot, enabling maintenance prevented me from opening the task interface. The maintenance handler ran after the access-control layer. The host needed for the approval entrance was absent from its pass-through list, so an authenticated operator still reached the maintenance page.

My initial working arrangement treated maintenance mode and the approval route as compatible. The observed result showed a missing exception between them. Authentication was not sufficient: the next handler could still prevent access to the place where I needed to approve the operational work.

The assistant added pass-through hosts and ran the gate runbook once by hand because the approval entrance was blocked. The deviation was recorded. This was a concrete departure from the normal path, rather than an approval completed through the task interface.

I do not have the runbook receipt or the detailed deviation record in this account, and the browser outcome after the correction is unknown. Adding the pass-through entries describes what changed; it does not establish that the corrected route was then usable. The episode leaves me with a dependency to examine: a maintenance control can obstruct the human action required to operate that same control.

## A reboot hung, and two explanations remained missing

The control-plane reboot hung during shutdown. Remote shell access remained down until power was cut. The approved change later verified, but that eventual verification did not explain the hang.

I initially had an approved reboot followed by verification as the operational path. The shutdown interruption forced attention onto what happened between starting the action and regaining a usable machine. The previous boot journal ended during container scope teardown without a clean journal-stop ending. The October 20 boot also lacked a clean ending, while older boots ended cleanly.

The observations also exposed missing shutdown protections. All nodes had a shutdown grace period of 0s, the runtime watchdog setting was 0, and no watchdog device was present. A server-side dry-run drain was blocked by four single-instance database clusters whose disruption budgets allowed 0 disruptions. These were relevant constraints and missing controls, but none established the cause of the hang.

Subsequent work introduced shutdown hardening. I can describe that response without claiming it repaired a diagnosed cause. There is no collected journal or verifier receipt in this account that proves why shutdown stopped where it did.

There was also a separate verification gap. A pending verifier later resolved without a change, and neither the cause nor the duration of that delay is established. The eventual verified state leaves both gaps intact: why the reboot hung, and why verification remained pending before resolving. I cannot join them into a single explanation merely because they occurred around the same approved change.

## A hardening check looked at the running state

The first live hardening run left the node agent without a shutdown inhibitor. The installer's hardening check detected that absence. At first, the configuration work suggested that the shutdown delay had been arranged; the live check showed that the intended protection was not present.

A later-sorting operating-system update override took precedence over the node agent's override and set the maximum inhibition delay to 30s. The node agent reported timing out after 5 attempts while waiting for the value to become 2m0s; it still saw 30s. The issue was not merely whether the desired configuration text existed. The effective value prevented the expected inhibitor from being held.

The correction specified a later-sorting override with 120s and a restart of the login manager only when its live value differed. That change addressed the precedence problem recorded for the absent inhibitor. It did not explain the earlier reboot hang.

I have no post-correction live inhibitor receipt or successful future reboot in this account. What I can retain is the first-run detection: a check of the running state caught a difference between intended configuration and active protection. Whether the correction produced and sustained the desired protection remains open here.

## Patterns I think I see

Could a check be most misleading when it succeeds at a nearby question? A recent run, admitted script, synchronized deployment, or authenticated request can all be real without establishing the next fact I need.

Is evidence freshness partly a responsibility for producing evidence, rather than only rejecting old evidence? My stale measurements and missing action markers suggest a question about the whole path from an action or measurement to its acceptance.

Could recovery dependencies deserve the same scrutiny as execution dependencies? The login workaround and maintenance handler each obstructed an approval entrance, although their mechanisms differed.

Does convenient knowledge become risky when I stop checking its scope? Remembered credential instructions and a broad routing description both looked usable until a more specific boundary contradicted them.

Might live read-back be most valuable where configuration layers can disagree? The remaining gateway listener and absent shutdown inhibitor make me want to ask that question without treating either correction as proof of general reliability.

## Limits

These are selected episodes from one small-scale, fail-fast-tolerant home lab, not a complete operational census. I have no denominator for failure rates, no cross-team or cross-engine replication, and no equivalent implementation without a workflow engine as a comparison. I cannot separate the costs of Camunda from those of any added coordination layer through these accounts.

I am both practitioner and researcher. My preferences and blind spots can shape the design, the errors, their recording, and my interpretation. AI assistants helped perform work and write the records; my acceptance of an account is not independent corroboration.

The evidence varies between recorded changes, session observations, and reported outcomes. Missing pipeline receipts, browser results, journal material, and live read-backs leave specific outcomes unresolved. A correction in version control does not establish deployment or successful behavior, and later success does not retrospectively prove a cause. These accounts support description and questions, not comparative reliability, reduced effort, architectural superiority, or generalized AI-safety claims.

## Open questions

How should I make each verifier establish that its evidence belongs to the action it is checking? How should CI distinguish a missing freshness requirement from a runtime failure to meet it?

What would show that a preview exercised the execution boundary that matters? How should evidence-refresh work be maintained so that a correctly enforced freshness rule does not repeatedly obstruct unrelated changes?

How can I preserve an approval and recovery entrance when maintenance or identity work affects that entrance? What evidence should an exceptional manual execution leave when the normal workflow path is unavailable?

What observations would establish the shutdown hang's cause, explain the pending verifier, and demonstrate that the corrected inhibitor is active? A successful future reboot would be useful, but would not by itself answer every causal question.

## Related work

I offer this short orientation, not a systematic survey, to place questions from my episodes beside a limited set of sources. I did not open the full text of every source: the account of Sagas rests on secondary summaries, the account of Helland's article on citations of it, and for Accelerate and Continuous Delivery I give the bibliographic details as commonly cited. I therefore treat these connections as reading directions, with no claim that my account confirms, replicates, or extends the sources. My records describe one home lab, and their missing receipts and unresolved outcomes constrain every connection below.

Van der Aalst's BPM survey presents process models as means both to configure information systems and to analyse and improve the processes they describe; its topics include model analysis, process mining, flexibility, and reuse [1]. In “A preview that could not grant runtime privilege,” I wonder whether analysing the process model alongside the selected worker's execution contract would help me distinguish admission and syntax checks from the authority needed for execution. The episode leaves that as a question: removing the incompatible action runbook did not establish a successful timer operation, much less the effectiveness of a modelling approach.

Garcia-Molina and Salem address long-lived transactions that retain database resources and delay shorter transactions; their saga uses compensating steps for backward recovery, with business-specific compensation restoring the original or an equivalent state rather than ACID rollback [2]. Helland's article is cited for treating idempotence as what makes repeated work acceptable when messages are retried [5]. In “Recovery needed the login path I had disturbed,” I might use compensation and retry tolerance as separate questions about the cleanup path: what state should cleanup restore, and what would make repeating it acceptable? The retained listener and manual pruning receipt do not establish that this operational sequence was a saga or that its cleanup was idempotent.

Parasuraman, Sheridan, and Wickens distinguish information acquisition, information analysis, decision and action selection, and action implementation, allowing different automation levels across these functions; they evaluate human-performance consequences first, then reliability and consequence costs, and note that automation changes human activity and can add coordination demands [3]. In “The maintenance page blocked its own approval entrance,” I might use those distinctions to ask how automated implementation affected my ability to perform the human approval activity. The recorded exception, without a subsequent browser outcome, gives me no basis for choosing an optimal automation level or asserting that the revised arrangement preserved access.

Continuous Delivery describes a deployment pipeline with progressively wider testing stages; OpenGitOps specifies declarative, versioned and immutable, automatically pulled, continuously reconciled desired state [8, 6]. Accelerate draws on the 2014–2017 State of DevOps Report programme, reports that speed and stability are not trade-offs, and discusses deployment frequency, change lead time, change failure rate, and restoration time; commentary on the book also warns that making the metrics targets can create wrong incentives [7]. In “A failed gate followed by a successful shell command,” I would ask how the pipeline's result actually controlled delivery and how the worker's delivered checkout related to desired state. Two pushes and a later passing pipeline supply neither those four delivery measures nor evidence about a speed–stability relationship on my platform.

Runeson and Host offer practices and checklists adapted from other fields to software engineering, arguing that case studies suit contemporary phenomena studied in their natural context [4]. For “A reboot hung, and two explanations remained missing,” I might use that methodological orientation to scrutinise how I separate observations from explanations, while retaining the absent causal evidence and my combined practitioner–researcher role. This does not establish that my AI-written account meets their guidelines. Gate policy and staged enforcement as a separate decision layer, AI decision authority and oversight beyond automation levels, and operations recovery drills remain unsurveyed here; I retain them as reading questions rather than claims of gaps or novelty.

## References


1. Wil M. P. van der Aalst. "Business Process Management: A Comprehensive Survey." ISRN Software Engineering, vol. 2013, Article ID 507984, 37 pages, February 2013. DOI 10.1155/2013/507984.

2. Hector Garcia-Molina and Kenneth Salem. "Sagas." Proceedings of the 1987 ACM SIGMOD International Conference on Management of Data, pp. 249-259 (Princeton technical report TR-070-87, January 1987).

3. Raja Parasuraman, Thomas B. Sheridan and Christopher D. Wickens. "A model for types and levels of human interaction with automation." IEEE Transactions on Systems, Man, and Cybernetics, Part A, vol. 30, no. 3, pp. 286-297, 2000.

4. Per Runeson and Martin Host. "Guidelines for conducting and reporting case study research in software engineering." Empirical Software Engineering 14(2), pp. 131-164, 2009. DOI 10.1007/s10664-008-9102-8.

5. Pat Helland. "Idempotence Is Not a Medical Condition." ACM Queue 10(4), 2012, p. 30.

6. OpenGitOps. "OpenGitOps 1.0" principles (announcement dated 8 November 2021, by Dan Garfield and Scott Rigby; https://opengitops.dev/blog/1.0-announcement).

7. Nicole Forsgren, Jez Humble and Gene Kim. "Accelerate: Building and Scaling High Performing Technology Organizations." IT Revolution, 2018 (details as commonly cited).

8. Jez Humble and David Farley. "Continuous Delivery: Reliable Software Releases through Build, Test, and Deployment Automation." Addison-Wesley, 2010 (year as commonly cited).
