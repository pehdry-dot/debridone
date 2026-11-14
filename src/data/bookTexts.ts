/*
 * Reading material for the Books module.
 *
 * Public-domain works appear as short opening excerpts (full texts are linked
 * to Project Gutenberg from each book page). The "Field Guide" and
 * "Lighthouse" pieces are original text written for this prototype.
 */

export interface BookText { chapters: { title: string; paragraphs: string[] }[] }

export const BOOK_TEXTS: Record<string, BookText> = {
  'pride-and-prejudice': {
    chapters: [{
      title: 'Chapter 1',
      paragraphs: [
        'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.',
        'However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters.',
        '“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”',
        'Mr. Bennet replied that he had not.',
        '“But it is,” returned she; “for Mrs. Long has just been here, and she told me all about it.”',
        'Mr. Bennet made no answer.',
        '“Do you not want to know who has taken it?” cried his wife impatiently.',
        '“You want to tell me, and I have no objection to hearing it.”',
        'This was invitation enough.',
        '“Why, my dear, you must know, Mrs. Long says that Netherfield is taken by a young man of large fortune from the north of England; that he came down on Monday in a chaise and four to see the place, and was so much delighted with it, that he agreed with Mr. Morris immediately; that he is to take possession before Michaelmas, and some of his servants are to be in the house by the end of next week.”',
        '“What is his name?”',
        '“Bingley.”',
        '“Is he married or single?”',
        '“Oh! Single, my dear, to be sure! A single man of large fortune; four or five thousand a year. What a fine thing for our girls!”',
        '“How so? How can it affect them?”',
        '“My dear Mr. Bennet,” replied his wife, “how can you be so tiresome! You must know that I am thinking of his marrying one of them.”',
        '“Is that his design in settling here?”',
        '“Design! Nonsense, how can you talk so! But it is very likely that he may fall in love with one of them, and therefore you must visit him as soon as he comes.”',
        '“I see no occasion for that. You and the girls may go, or you may send them by themselves, which perhaps will be still better, for as you are as handsome as any of them, Mr. Bingley may like you the best of the party.”',
        '“My dear, you flatter me. I certainly have had my share of beauty, but I do not pretend to be anything extraordinary now. When a woman has five grown-up daughters, she ought to give over thinking of her own beauty.”',
        '“In such cases, a woman has not often much beauty to think of.”',
        '“But, my dear, you must indeed go and see Mr. Bingley when he comes into the neighbourhood.”',
        '“It is more than I engage for, I assure you.”',
        '“But consider your daughters. Only think what an establishment it would be for one of them. Sir William and Lady Lucas are determined to go, merely on that account, for in general, you know, they visit no newcomers. Indeed you must go, for it will be impossible for us to visit him if you do not.”',
        '“You are over-scrupulous, surely. I dare say Mr. Bingley will be very glad to see you; and I will send a few lines by you to assure him of my hearty consent to his marrying whichever he chooses of the girls; though I must throw in a good word for my little Lizzy.”',
        '“I desire you will do no such thing. Lizzy is not a bit better than the others; and I am sure she is not half so handsome as Jane, nor half so good-humoured as Lydia. But you are always giving her the preference.”',
        '“They have none of them much to recommend them,” replied he; “they are all silly and ignorant like other girls; but Lizzy has something more of quickness than her sisters.”',
        '“Mr. Bennet, how can you abuse your own children in such a way? You take delight in vexing me. You have no compassion for my poor nerves.”',
        '“You mistake me, my dear. I have a high respect for your nerves. They are my old friends. I have heard you mention them with consideration these last twenty years at least.”',
        '“Ah, you do not know what I suffer.”',
        '“But I hope you will get over it, and live to see many young men of four thousand a year come into the neighbourhood.”',
        '“It will be no use to us, if twenty such should come, since you will not visit them.”',
        '“Depend upon it, my dear, that when there are twenty, I will visit them all.”',
        'Mr. Bennet was so odd a mixture of quick parts, sarcastic humour, reserve, and caprice, that the experience of three-and-twenty years had been insufficient to make his wife understand his character. Her mind was less difficult to develop. She was a woman of mean understanding, little information, and uncertain temper. When she was discontented, she fancied herself nervous. The business of her life was to get her daughters married; its solace was visiting and news.',
      ],
    }],
  },
  alice: {
    chapters: [{
      title: 'Chapter I — Down the Rabbit-Hole',
      paragraphs: [
        'Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do: once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it, “and what is the use of a book,” thought Alice “without pictures or conversations?”',
        'So she was considering in her own mind (as well as she could, for the hot day made her feel very sleepy and stupid), whether the pleasure of making a daisy-chain would be worth the trouble of getting up and picking the daisies, when suddenly a White Rabbit with pink eyes ran close by her.',
        'There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit say to itself, “Oh dear! Oh dear! I shall be late!” (when she thought it over afterwards, it occurred to her that she ought to have wondered at this, but at the time it all seemed quite natural); but when the Rabbit actually took a watch out of its waistcoat-pocket, and looked at it, and then hurried on, Alice started to her feet, for it flashed across her mind that she had never before seen a rabbit with either a waistcoat-pocket, or a watch to take out of it, and burning with curiosity, she ran across the field after it, and fortunately was just in time to see it pop down a large rabbit-hole under the hedge.',
        'In another moment down went Alice after it, never once considering how in the world she was to get out again.',
        'The rabbit-hole went straight on like a tunnel for some way, and then dipped suddenly down, so suddenly that Alice had not a moment to think about stopping herself before she found herself falling down a very deep well.',
        'Either the well was very deep, or she fell very slowly, for she had plenty of time as she went down to look about her and to wonder what was going to happen next. First, she tried to look down and make out what she was coming to, but it was too dark to see anything; then she looked at the sides of the well, and noticed that they were filled with cupboards and book-shelves; here and there she saw maps and pictures hung upon pegs. She took down a jar from one of the shelves as she passed; it was labelled “ORANGE MARMALADE”, but to her great disappointment it was empty: she did not like to drop the jar for fear of killing somebody underneath, so managed to put it into one of the cupboards as she fell past it.',
        '“Well!” thought Alice to herself, “after such a fall as this, I shall think nothing of tumbling down stairs! How brave they’ll all think me at home! Why, I wouldn’t say anything about it, even if I fell off the top of the house!” (Which was very likely true.)',
        'Down, down, down. Would the fall never come to an end?',
      ],
    }],
  },
  'moby-dick': {
    chapters: [{
      title: 'Chapter 1 — Loomings',
      paragraphs: [
        'Call me Ishmael. Some years ago—never mind how long precisely—having little or no money in my purse, and nothing particular to interest me on shore, I thought I would sail about a little and see the watery part of the world. It is a way I have of driving off the spleen and regulating the circulation. Whenever I find myself growing grim about the mouth; whenever it is a damp, drizzly November in my soul; whenever I find myself involuntarily pausing before coffin warehouses, and bringing up the rear of every funeral I meet; and especially whenever my hypos get such an upper hand of me, that it requires a strong moral principle to prevent me from deliberately stepping into the street, and methodically knocking people’s hats off—then, I account it high time to get to sea as soon as I can. This is my substitute for pistol and ball. With a philosophical flourish Cato throws himself upon his sword; I quietly take to the ship. There is nothing surprising in this. If they but knew it, almost all men in their degree, some time or other, cherish very nearly the same feelings towards the ocean with me.',
        'There now is your insular city of the Manhattoes, belted round by wharves as Indian isles by coral reefs—commerce surrounds it with her surf. Right and left, the streets take you waterward. Its extreme downtown is the battery, where that noble mole is washed by waves, and cooled by breezes, which a few hours previous were out of sight of land. Look at the crowds of water-gazers there.',
        'Circumambulate the city of a dreamy Sabbath afternoon. Go from Corlears Hook to Coenties Slip, and from thence, by Whitehall, northward. What do you see?—Posted like silent sentinels all around the town, stand thousands upon thousands of mortal men fixed in ocean reveries. Some leaning against the spiles; some seated upon the pier-heads; some looking over the bulwarks of ships from China; some high aloft in the rigging, as if striving to get a still better seaward peep. But these are all landsmen; of week days pent up in lath and plaster—tied to counters, nailed to benches, clinched to desks. How then is this? Are the green fields gone? What do they here?',
      ],
    }],
  },
  'two-cities': {
    chapters: [{
      title: 'Book the First, Chapter I — The Period',
      paragraphs: [
        'It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness, it was the epoch of belief, it was the epoch of incredulity, it was the season of Light, it was the season of Darkness, it was the spring of hope, it was the winter of despair, we had everything before us, we had nothing before us, we were all going direct to Heaven, we were all going direct the other way—in short, the period was so far like the present period, that some of its noisiest authorities insisted on its being received, for good or for evil, in the superlative degree of comparison only.',
        'There were a king with a large jaw and a queen with a plain face, on the throne of England; there were a king with a large jaw and a queen with a fair face, on the throne of France. In both countries it was clearer than crystal to the lords of the State preserves of loaves and fishes, that things in general were settled for ever.',
        'It was the year of Our Lord one thousand seven hundred and seventy-five. Spiritual revelations were conceded to England at that favoured period, as at this.',
      ],
    }],
  },
  frankenstein: {
    chapters: [{
      title: 'Letter 1',
      paragraphs: [
        'To Mrs. Saville, England.',
        'St. Petersburgh, Dec. 11th, 17—.',
        'You will rejoice to hear that no disaster has accompanied the commencement of an enterprise which you have regarded with such evil forebodings. I arrived here yesterday, and my first task is to assure my dear sister of my welfare and increasing confidence in the success of my undertaking.',
        'I am already far north of London, and as I walk in the streets of Petersburgh, I feel a cold northern breeze play upon my cheeks, which braces my nerves and fills me with delight. Do you understand this feeling? This breeze, which has travelled from the regions towards which I am advancing, gives me a foretaste of those icy climes. Inspirited by this wind of promise, my daydreams become more fervent and vivid. I try in vain to be persuaded that the pole is the seat of frost and desolation; it ever presents itself to my imagination as the region of beauty and delight. There, Margaret, the sun is for ever visible, its broad disk just skirting the horizon and diffusing a perpetual splendour.',
      ],
    }],
  },
  'art-of-war': {
    chapters: [{
      title: 'I. Laying Plans',
      paragraphs: [
        '1. Sun Tzŭ said: The art of war is of vital importance to the State.',
        '2. It is a matter of life and death, a road either to safety or to ruin. Hence it is a subject of inquiry which can on no account be neglected.',
        '3. The art of war, then, is governed by five constant factors, to be taken into account in one’s deliberations, when seeking to determine the conditions obtaining in the field.',
        '4. These are: (1) The Moral Law; (2) Heaven; (3) Earth; (4) The Commander; (5) Method and discipline.',
        '5, 6. The Moral Law causes the people to be in complete accord with their ruler, so that they will follow him regardless of their lives, undismayed by any danger.',
        '7. Heaven signifies night and day, cold and heat, times and seasons.',
        '8. Earth comprises distances, great and small; danger and security; open ground and narrow passes; the chances of life and death.',
        '9. The Commander stands for the virtues of wisdom, sincerity, benevolence, courage and strictness.',
        '10. By method and discipline are to be understood the marshalling of the army in its proper subdivisions, the graduations of rank among the officers, the maintenance of roads by which supplies may reach the army, and the control of military expenditure.',
        '11. These five heads should be familiar to every general: he who knows them will be victorious; he who knows them not will fail.',
        '[Verses 12–17 are omitted from this excerpt.]',
        '18. All warfare is based on deception.',
        '19. Hence, when able to attack, we must seem unable; when using our forces, we must seem inactive; when we are near, we must make the enemy believe we are far away; when far away, we must make him believe we are near.',
        '20. Hold out baits to entice the enemy. Feign disorder, and crush him.',
        '21. If he is secure at all points, be prepared for him. If he is in superior strength, evade him.',
        '22. If your opponent is of choleric temper, seek to irritate him. Pretend to be weak, that he may grow arrogant.',
        '23. If he is taking his ease, give him no rest. If his forces are united, separate them.',
        '24. Attack him where he is unprepared, appear where you are not expected.',
        '25. These military devices, leading to victory, must not be divulged beforehand.',
        '26. Now the general who wins a battle makes many calculations in his temple ere the battle is fought. The general who loses a battle makes but few calculations beforehand. Thus do many calculations lead to victory, and few calculations to defeat: it is by attention to this point that I can foresee who is likely to win or lose.',
      ],
    }],
  },
  'sherlock-holmes': {
    chapters: [{
      title: 'A Scandal in Bohemia — I.',
      paragraphs: [
        'To Sherlock Holmes she is always the woman. I have seldom heard him mention her under any other name. In his eyes she eclipses and predominates the whole of her sex. It was not that he felt any emotion akin to love for Irene Adler. All emotions, and that one particularly, were abhorrent to his cold, precise but admirably balanced mind. He was, I take it, the most perfect reasoning and observing machine that the world has seen, but as a lover he would have placed himself in a false position. He never spoke of the softer passions, save with a gibe and a sneer. They were admirable things for the observer—excellent for drawing the veil from men’s motives and actions. But for the trained reasoner to admit such intrusions into his own delicate and finely adjusted temperament was to introduce a distracting factor which might throw a doubt upon all his mental results. Grit in a sensitive instrument, or a crack in one of his own high-power lenses, would not be more disturbing than a strong emotion in a nature such as his. And yet there was but one woman to him, and that woman was the late Irene Adler, of dubious and questionable memory.',
      ],
    }],
  },
  'field-guide': {
    chapters: [
      {
        title: '1 · Welcome',
        paragraphs: [
          'DebridOne is a single home for the things you watch, hear, play and read. This guide is itself a demo book: it exists so you can try the reader — change the typeface, switch to sepia, drop a bookmark — on text that also happens to be useful.',
          'Everything you see on first launch is sample content. Films are open movies and public-domain classics streamed from the Internet Archive. Music is generated in your browser. Manga pages are drawn by code. Games are real open-source projects that link to their official sites. Nothing is unlocked by a hidden server, and nothing claims to be something it is not.',
          'Your changes — favourites, progress, edits, themes — are saved in this browser and survive a refresh. You can export or erase them at any time from Settings → Privacy.',
        ],
      },
      {
        title: '2 · Your library, your rules',
        paragraphs: [
          'Every title, from a film to a chiptune EP, can be saved, favourited and placed in collections. Collections are deliberately type-agnostic: put a game, its soundtrack and the novel that inspired it side by side.',
          'Open any item and choose Edit metadata to rewrite its title, description, artwork, genres, tags or your own custom fields. DebridOne keeps the original record untouched and layers your edits on top, so Restore original is always one click away. When a metadata provider disagrees with you, you win — unless you decide otherwise in the conflict view.',
          'Bring your own files, too. Audio, video, EPUB, PDF, plain text and CBZ comics can be imported from your device. They are stored in this browser’s IndexedDB and never uploaded anywhere.',
        ],
      },
      {
        title: '3 · Sources and safety',
        paragraphs: [
          'Sources tell DebridOne where content may come from. The built-in ones point at legal, openly licensed material. You can add your own by filling in a form or importing a manifest file, and nobody has to approve it first.',
          'That freedom comes with guard rails. Manifests are validated before they are accepted. Private network addresses are refused. Nothing in a manifest is ever executed as code. Executable downloads require your explicit consent. And every source you add is labelled Unreviewed, because that is exactly what it is.',
          'Debrid providers such as Real-Debrid and Premiumize are represented by demo adapters. They show how an authorised personal file list and stream links would flow through the app once a real, server-side integration is configured.',
        ],
      },
      {
        title: '4 · Players and readers',
        paragraphs: [
          'The video player remembers where you stopped, supports subtitles, playback speed, picture-in-picture and fullscreen, and responds to the keyboard: space to pause, arrow keys to seek, F for fullscreen, M to mute.',
          'The music bar stays with you as you browse. Build a queue, shuffle it, repeat it, or expand the Now Playing view. The manga reader handles right-to-left, left-to-right, vertical and continuous webtoon modes. This reader offers paged or scrolling layouts, four type families and three paper colours.',
        ],
      },
      {
        title: '5 · Making it yours',
        paragraphs: [
          'DebridOne Pro is the control centre for appearance. In this prototype it runs in Demo Pro Mode: every option is unlocked, there is no checkout, and no payment is ever taken.',
          'Pick an accent colour, round the corners, tighten the density, set a wallpaper, rearrange the sidebar, compose your own home dashboards, or write CSS. Themes can be exported as a file and shared. If you ever get lost, Reset theme brings back the default midnight purple.',
          'That is the whole tour. Close this book and go press some buttons.',
        ],
      },
    ],
  },
  lighthouse: {
    chapters: [
      {
        title: 'I · The Keeper',
        paragraphs: [
          'The lighthouse stood where the chart ran out. Past it the paper was blank, and the sea, obligingly, was blank as well: grey water under grey sky, with nothing to say which was which.',
          'Wen had kept the light for eleven winters. She trimmed nothing and wound nothing — the lamp was electric and the lens turned itself — but she walked the gallery every dusk all the same, because a light that nobody watches is only a lamp.',
          'On the first night of the twelfth winter, something out in the blank part of the map blinked back.',
        ],
      },
      {
        title: 'II · The Answer',
        paragraphs: [
          'Three long, two short. She counted it four times before she believed it, then went inside and counted it again through the window, as though glass might make it more reasonable.',
          'There was no code for it in the book. There was no book for that side of the lighthouse at all. So Wen did the only thing a keeper can do with a light: she covered the lens with her coat, uncovered it, covered it again. Three long. Two short.',
          'The dark considered this. Then, a little closer than before, it answered: two long, three short — the same sentence, turned around, the way you repeat a word in a language you are trying to learn.',
        ],
      },
      {
        title: 'III · The Edge',
        paragraphs: [
          'By midwinter they had a grammar. Long-long meant weather. Short-short-long meant something like are you still there, and Wen always was.',
          'In spring the survey ship came, as it did every few years, to ask whether the map needed changing. Wen looked at the blank quarter of the chart for a long moment.',
          '“No,” she said. “Leave it. But draw the edge in pencil.”',
        ],
      },
    ],
  },
};
