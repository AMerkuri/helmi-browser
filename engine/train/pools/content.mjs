// Page elements the model must leave alone, hand-written imitations. Each has its own shape
// (see make-train.mjs SHAPES): headers and nav bars carry "Log in" and "Subscribe" links, carts
// carry prices and "Checkout", articles talk about cookies or privacy.
export default {
  header: {
    en: [
      { t: 'News Sport Business Culture Opinion Log in Subscribe', b: ['Menu', 'Search', 'Log in', 'Subscribe'] },
      { t: 'Shop Men Women Kids Sale Sign in Bag (2)', b: ['Open menu', 'Search', 'Sign in', 'Bag'] },
      { t: 'The Daily Ledger Sign up for free Log in', b: ['Sign up for free', 'Log in'] },
    ],
    de: [{ t: 'Politik Wirtschaft Sport Kultur Anmelden Abo', b: ['Menü', 'Suche', 'Anmelden', 'Abo'] }],
    fr: [{ t: 'Actualités Sport Culture Se connecter S’abonner', b: ['Menu', 'Rechercher', 'Se connecter', 'S’abonner'] }],
    et: [
      { t: 'Uudised Sport Majandus Kultuur Logi sisse Telli', b: ['Menüü', 'Otsi', 'Logi sisse', 'Telli'] },
      { t: 'Avaleht Tooted Kampaaniad Ostukorv (1) Minu konto', b: ['Menüü', 'Ostukorv', 'Minu konto'] },
    ],
    fi: [{ t: 'Uutiset Urheilu Talous Kirjaudu Tilaa', b: ['Valikko', 'Haku', 'Kirjaudu', 'Tilaa'] }],
    sv: [{ t: 'Nyheter Sport Nöje Logga in Prenumerera', b: ['Meny', 'Sök', 'Logga in', 'Prenumerera'] }],
    lv: [{ t: 'Ziņas Sports Bizness Pieslēgties Abonēt', b: ['Izvēlne', 'Meklēt', 'Pieslēgties', 'Abonēt'] }],
    pl: [{ t: 'Wiadomości Sport Biznes Zaloguj Subskrybuj', b: ['Menu', 'Szukaj', 'Zaloguj', 'Subskrybuj'] }],
  },
  bottom_nav: {
    en: [{ t: 'Home Search Saved Trips Sign in', b: ['Home', 'Search', 'Saved', 'Trips', 'Sign in'] }, { t: 'For you Following Explore Notifications Profile', b: ['For you', 'Following', 'Explore', 'Notifications', 'Profile'] }],
    et: [{ t: 'Avaleht Otsing Lemmikud Ostukorv Konto', b: ['Avaleht', 'Otsing', 'Lemmikud', 'Ostukorv', 'Konto'] }],
    de: [{ t: 'Start Suche Merkliste Warenkorb Konto', b: ['Start', 'Suche', 'Merkliste', 'Warenkorb', 'Konto'] }],
  },
  cart: {
    en: [
      { t: 'Added to bag. Linen shirt, size M, €49.00. Subtotal €49.00. Free delivery on orders over €60.', b: ['View bag', 'Checkout', 'Continue shopping', 'Close'] },
      { t: 'Your cart (2 items) Wool socks €12.00 Rain jacket €89.00 Total €101.00', b: ['Checkout', 'Edit cart', '×'] },
      { t: 'Free shipping on all orders over $50 · Easy returns', b: [] },
    ],
    de: [{ t: 'Zum Warenkorb hinzugefügt. Laufschuhe, Größe 42, 89,95 €. Zwischensumme 89,95 €', b: ['Zum Warenkorb', 'Zur Kasse', 'Weiter einkaufen'] }],
    et: [
      { t: 'Lisatud ostukorvi. Villased sokid, suurus 39–42, 12,90 €. Kokku 12,90 €. Tasuta tarne alates 50 €.', b: ['Vaata ostukorvi', 'Vormista tellimus', 'Jätka ostlemist'] },
      { t: 'Ostukorv (3) Kokku 54,70 €', b: ['Maksma', 'Sulge'] },
    ],
    fi: [{ t: 'Lisätty ostoskoriin. Sadetakki, koko M, 89,00 €. Välisumma 89,00 €', b: ['Ostoskoriin', 'Kassalle', 'Jatka ostoksia'] }],
    sv: [{ t: 'Tillagd i varukorgen. Ullmössa 29,00 €. Summa 29,00 €', b: ['Till kassan', 'Fortsätt handla'] }],
    lv: [{ t: 'Pievienots grozam. Vilnas zeķes, 12,90 €. Kopā 12,90 €', b: ['Skatīt grozu', 'Noformēt pasūtījumu', 'Turpināt iepirkties'] }],
    pl: [{ t: 'Dodano do koszyka. Kurtka przeciwdeszczowa, 299 zł. Razem 299 zł', b: ['Przejdź do koszyka', 'Kontynuuj zakupy'] }],
  },
  alert: {
    en: [
      { t: 'Weather warning: strong winds expected this evening. Check the latest forecast.', b: ['Details', 'Dismiss'] },
      { t: 'BREAKING: Parliament passes new budget after late-night vote', b: ['Read more'] },
      { t: 'Live: Election results as they come in', b: ['Follow live'] },
    ],
    et: [{ t: 'Tormihoiatus: õhtul on oodata tugevat tuult. Vaata ilmateadet.', b: ['Loe lähemalt', 'Sulge'] }],
    fi: [{ t: 'Sää: myrskyvaroitus Etelä-Suomeen illalla.', b: ['Lue lisää'] }],
    de: [{ t: 'Eilmeldung: Bahnstreik ab morgen früh – alle Infos im Liveblog', b: ['Zum Liveblog'] }],
  },
  article: {
    en: [
      { t: 'How browser cookies work, and what the new EU rules mean for you. Cookies are small files that sites store on your device. Under the new rules, rejecting them must be as easy as accepting.', b: ['Share', 'Save'] },
      { t: 'Our privacy policy explains what data we collect and why. You can request a copy of your data at any time by contacting our data protection officer.', b: ['Contact us'] },
      { t: 'Chocolate chip cookies: 250 g butter, 200 g sugar, 2 eggs, 300 g flour. Bake for 12 minutes at 180 °C.', b: ['Print recipe', 'Save'] },
    ],
    et: [{ t: 'Kuidas küpsised töötavad ja mida uued reeglid tähendavad. Küpsised on väikesed failid, mida veebilehed sinu seadmesse salvestavad.', b: ['Jaga', 'Salvesta'] }],
    de: [{ t: 'Was die neue Datenschutz-Verordnung für Verbraucher bedeutet: Ein Überblick über Ihre Rechte.', b: ['Teilen', 'Merken'] }],
  },
  menu: {
    en: [
      { t: 'Home World Politics Business Tech Science Health Sport Culture Travel Podcasts Newsletters', b: ['Close menu', 'Home', 'World', 'Politics', 'Business', 'Tech', 'Science', 'Sport'] },
      { t: 'Language English Deutsch Français Español Eesti Suomi', b: ['English', 'Deutsch', 'Français', 'Español', 'Eesti', 'Suomi'] },
      { t: 'Filter Size Colour Price Brand Sort by Show 128 results', b: ['Clear all', 'Show results', 'Close'] },
      { t: 'Share this article Facebook X WhatsApp Email Copy link', b: ['Facebook', 'X', 'WhatsApp', 'Email', 'Copy link', 'Cancel'] },
    ],
    et: [{ t: 'Keel Eesti English Русский', b: ['Eesti', 'English', 'Русский'] }, { t: 'Filtreeri Suurus Värv Hind Kuva 64 toodet', b: ['Tühjenda', 'Kuva tooted', 'Sulge'] }],
    fi: [{ t: 'Jaa artikkeli Facebook WhatsApp Sähköposti Kopioi linkki', b: ['Facebook', 'WhatsApp', 'Sähköposti', 'Kopioi linkki'] }],
  },
  player: {
    en: [
      { t: 'Now playing: Morning News Podcast 12:04 / 31:20', b: ['Pause', 'Skip 15s', 'Close player'], video: false },
      { t: 'LIVE Watch: the press conference', b: ['Mute', 'Full screen', 'Close'], video: true },
    ],
    et: [{ t: 'Otse: valitsuse pressikonverents', b: ['Vaigista', 'Täisekraan', 'Sulge'], video: true }],
  },
  widget: {
    en: [
      { t: 'Reading progress 45% · 6 min left', b: [] },
      { t: 'Table of contents 1. Introduction 2. Getting started 3. Configuration 4. FAQ', b: ['Introduction', 'Getting started', 'Configuration', 'FAQ'] },
      { t: 'Choose delivery: Home delivery €4.90, Parcel locker €2.90, Store pickup free', b: ['Home delivery', 'Parcel locker', 'Store pickup'] },
      { t: 'Step 2 of 4: Shipping address', b: ['Back', 'Continue to payment'] },
      { t: 'Comments (128) Write a comment… Sort by newest', b: ['Post', 'Close'] },
      { t: 'Score: HJK 2 – 1 KuPS · 78′', b: ['Live table'] },
      { t: 'Back to top', b: ['Back to top'] },
    ],
    et: [{ t: 'Samm 2/4: tarneviis', b: ['Tagasi', 'Jätka maksmisega'] }, { t: 'Kommentaarid (54) Kirjuta kommentaar…', b: ['Postita', 'Sulge'] }],
  },
};
