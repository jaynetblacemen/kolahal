---
title: People
description: Call, text and find contacts from the prompt.
---

```sh
call mum                  # dial a contact (alias: dial)
call +44 20 7946 0000     # or a number
sms alex running late     # start a message with text (alias: text)
contacts                  # favourites, or the first 20 contacts if you have none
contacts sam              # search (alias: contact)
```

- The first time you use one of these, Kolahal asks for the **Contacts** permission. Nothing is
  uploaded; contacts are only read to match names.
- After `call `, `sms ` or `contacts ` your contacts appear as suggestions right away, favourites (★)
  first, and narrow as you type a name or number. A contact with several numbers gets one row per
  number; choosing one fills in that number. `sms` always completes to the number, so you can type
  the message straight after it.
- If a name matches several contacts, they are listed instead: tap one to call, long-press to
  message, open the contact or copy the number.
- `call` opens the dialer with the number filled in. It never places the call by itself. `sms`
  opens your messaging app with the text pre-filled, ready for you to send.
- Contact and number lines in the log stay tappable later: tap dials, long-press offers call,
  message, open contact and copy number.
