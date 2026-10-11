"""Build the hand-made conversational ja/en retrieval eval -> data/conv_eval.json.

Design
- 50 queries a user would type/speak when searching their own day ("歯医者の予約いつだっけ").
- Each query has 1-2 relevant transcript segments (short, spoken style; some with ASR-like
  fillers "えーと/あのー" and no punctuation).
- Each query also has hand-written hard negatives that share surface keywords or topic but
  do not answer the query.
- Query types: ja_para (ja->ja paraphrase), ja_kw (ja->ja keyword overlap), en (en->en),
  x_ja2en (ja query -> en segment), x_en2ja (en query -> ja segment).
- ~2,000 filler segments are generated from templates over topics that are disjoint from the
  query topics (weather, TV, sports, food talk, work chit-chat ...), so they act as realistic
  background noise without accidentally becoming relevant.
Deterministic (seed 0).
"""
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (query_id, query, lang/type, [relevant segments], [hard negatives])
ITEMS = [
    ("q01", "歯医者の予約いつだっけ", "ja_kw",
     ["来週の木曜、午後三時に歯医者さん予約しといたからね"],
     ["歯医者ってほんと苦手なんだよね、あの削る音がさ", "美容院の予約、土曜の十時に取れたよ"]),
    ("q02", "先週話した旅行の件", "ja_para",
     ["来月の沖縄だけどさ、飛行機は那覇行きの朝の便でいいかな",
      "沖縄のホテル、国際通りの近くにしようと思ってるんだけど"],
     ["出張で大阪に行くのって来週だっけ", "旅行会社のパンフレットもう捨てていい？"]),
    ("q03", "鍵どこに置いたって言ってた？", "ja_kw",
     ["車のキーなら玄関の靴箱の上のトレーに入れといたよ"],
     ["鍵開けっぱなしで出かけちゃったかも、ちょっと心配", "合鍵作るのって駅前のお店でできるのかな"]),
    ("q04", "田中さんの誕生日プレゼント何にする？", "ja_para",
     ["田中さんへの贈り物、ハンドクリームのセットがいいんじゃないかって話になった"],
     ["田中さんって来月で異動するらしいよ", "誕生日ケーキはいちごのやつにしよう"]),
    ("q05", "病院で言われた薬の飲み方", "ja_para",
     ["先生がね、この錠剤は一日二回、朝と夜の食後に飲んでくださいって"],
     ["薬局のポイントカードどこいったかな", "病院の待ち時間ほんと長かった"]),
    ("q06", "引っ越しの見積もりっていくらだった", "ja_kw",
     ["引っ越し業者さん、全部込みで十二万円って言ってたよ"],
     ["引っ越し先の近くにスーパーあるといいね", "見積もりの書類、会社に置いてきちゃった"]),
    ("q07", "子供のお迎え誰が行くんだっけ", "ja_para",
     ["金曜日の保育園の送り迎えはおばあちゃんにお願いしてあるから"],
     ["保育園の運動会って雨だったら延期？", "子供の頃よく近所の川で遊んだなあ"]),
    ("q08", "会議の結論どうなった", "ja_para",
     ["じゃあ今回はリリースを二週間延ばすってことで決定にしましょう"],
     ["会議室の予約、また取れてなかったんだけど", "結論から言うと、まだ何も決まってないです"]),
    ("q09", "エアコン修理の人いつ来る", "ja_para",
     ["冷房直しに来る業者さん、土曜の午前中に来てくれるって"],
     ["エアコン新しいのに買い替えたほうが電気代安くなるかもね", "修理に出してたカメラ戻ってきたよ"]),
    ("q10", "夕飯何食べたいって言ってたっけ", "ja_para",
     ["今日の晩ごはんはさ、久しぶりにカレーが食べたいな"],
     ["昼ごはんのラーメン屋、めっちゃ混んでたね", "夕方になると急に寒くなるね"]),
    ("q11", "車検の期限", "ja_kw",
     ["うちの車、車検が十一月で切れるから早めに予約しないと"],
     ["車のタイヤ、そろそろ冬用に替えないとね", "期限切れのクーポン捨てといて"]),
    ("q12", "お金貸した件どうなった", "ja_para",
     ["こないだ立て替えた五千円、給料日に返すって言ってたよ"],
     ["銀行のアプリで振り込むのって手数料かかる？", "お金の話って友達とはしにくいよね"]),
    ("q13", "健康診断の結果", "ja_para",
     ["人間ドックの結果が来て、コレステロールがちょっと高めだって"],
     ["健康のためにジム通い始めようかな", "診断書って会社に出さないといけないの？"]),
    ("q14", "Wi-Fiのパスワード", "ja_kw",
     ["ネットのパスワード変えたから、新しいのは冷蔵庫に貼っといたよ"],
     ["パスワード忘れてログインできなくなっちゃった", "Wi-Fiが遅いのってルーターのせいかな"]),
    ("q15", "猫の予防接種いつ", "ja_para",
     ["ミケのワクチン、動物病院で来月の三日に打つことになったよ"],
     ["猫カフェ行ってみたいんだよね", "予防のためにマスクしていったほうがいいかな"]),
    ("q16", "母の日の花どうした", "ja_kw",
     ["お母さんへのカーネーション、花屋さんに配達頼んでおいた"],
     ["母が最近スマホ使えるようになってさ", "ベランダの花が枯れちゃった"]),
    ("q17", "健太の結婚式の日取り", "ja_para",
     ["健太のとこ、式は六月の第二土曜日に決まったんだって"],
     ["結婚して何年になるっけ、うちら", "健太って昔サッカー部だったよね"]),
    ("q18", "ゴミの日いつ", "ja_kw",
     ["燃えるゴミは月曜と木曜、ペットボトルは水曜日だよ"],
     ["ゴミ袋がもうなくなりそう", "日曜はどこも混んでるからやめとこう"]),
    ("q19", "スマホの契約変えた話", "ja_para",
     ["携帯の料金プラン、格安SIMに乗り換えて月三千円になったよ"],
     ["スマホの画面割れちゃった", "契約社員の更新って来年だっけ"]),
    ("q20", "保険の更新手続き", "ja_kw",
     ["自動車保険の更新案内が来てて、今月末までに手続きしないと"],
     ["保険の営業の人からまた電話あった", "パスポートの更新ってどこでやるんだっけ"]),
    ("q21", "資料の締め切りいつまで", "ja_para",
     ["えーとあのー例の資料なんですけどあさってまでに出してもらえれば大丈夫です"],
     ["プロジェクターの電源が入らないんですけど", "締め切り前ってなんで部屋の掃除したくなるんだろう"]),
    ("q22", "車どこに停めた", "ja_para",
     ["車はね駅の裏のコインパーキングに入れてあるから"],
     ["駐車違反の切符切られたことある？", "車の運転って最近してないなあ"]),
    ("q23", "来週の飲み会の店", "ja_kw",
     ["金曜の飲み会、駅前の焼き鳥屋さんで七時からね"],
     ["最近お酒弱くなっちゃって", "お店のレビュー見たら星三つだった"]),
    ("q24", "新しい部長ってどんな人", "ja_para",
     ["今度来た部長、話しやすくていい人そうだったよ"],
     ["部長に有給の申請出すの忘れてた", "新しいパソコンまだ届かないんだけど"]),
    ("q25", "昨日の映画どうだった", "ja_kw",
     ["昨日観た映画、最後泣いちゃった、すごく良かった"],
     ["映画館のポップコーンって高いよね", "昨日は一日中雨だったね"]),
    ("q26", "what did we decide about the budget", "en",
     ["okay so we agreed to cut the marketing budget by ten percent next quarter"],
     ["the budget meeting got moved again", "I really need to budget better for groceries"]),
    ("q27", "where are we meeting for lunch", "en",
     ["let's grab food at that Thai place across from the station around noon"],
     ["I already had lunch, sorry", "the meeting room on the fifth floor is booked all day"]),
    ("q28", "flight details for the London trip", "en",
     ["the London flight departs at nine forty from Haneda terminal three"],
     ["honestly I'm a bit afraid of flying", "London is so expensive these days"]),
    ("q29", "who is picking up the kids on Wednesday", "en",
     ["Sarah said she can grab the children from school on Wednesday"],
     ["kids these days are glued to their phones", "Wednesday is always my busiest day"]),
    ("q30", "the login bug", "en",
     ["users can't sign in on Safari because the session cookie isn't being set"],
     ["the login page design looks a bit dated", "there's a bug in my apartment, I think it's a spider"]),
    ("q31", "my workout schedule", "en",
     ["I'm going to hit the gym Monday, Wednesday and Friday mornings before work"],
     ["the gym membership fee went up again", "my schedule is packed this week"]),
    ("q32", "did the landlord fix the leak", "en",
     ["the landlord finally sent a plumber and the kitchen sink isn't dripping anymore"],
     ["my landlord raised the rent again", "someone leaked the product roadmap to the press"]),
    ("q33", "how do you make the pasta sauce", "en",
     ["you just simmer the tomatoes with garlic and a splash of olive oil for twenty minutes"],
     ["we're out of pasta, can you pick some up", "the sauce stained my white shirt"]),
    ("q34", "job interview result", "en",
     ["the recruiter called and they want me back for a final round next week"],
     ["interviewing candidates all day is exhausting", "the results of the survey came in"]),
    ("q35", "how did mom's surgery go", "en",
     ["mom's knee operation went well and she's going home on Tuesday"],
     ["my mother loves gardening", "the surgery department is on the third floor"]),
    ("q36", "サーバー移行の日程", "x_ja2en",
     ["we'll migrate the database servers over the weekend of the fifteenth"],
     ["the server room is way too cold", "can you send me the schedule for the offsite"]),
    ("q37", "クライアントが値段について何て言ってた", "x_ja2en",
     ["the client said our quote was too high and asked for a fifteen percent discount"],
     ["prices at the cafeteria went up again", "the client meeting is at two"]),
    ("q38", "シンガポール出張のホテル", "x_ja2en",
     ["I booked a hotel near the convention center in Singapore for four nights"],
     ["ホテルの朝ごはん美味しかったね", "business trips always mess up my sleep"]),
    ("q39", "新入社員の研修はいつから", "x_ja2en",
     ["the onboarding training for new hires starts on April third"],
     ["we're hiring two more engineers this year", "I started running again in April"]),
    ("q40", "契約書のチェック", "x_ja2en",
     ["legal still needs to review the contract before we can sign it"],
     ["my phone contract ends in March", "can you check if the door is locked"]),
    ("q41", "when is the recycling pickup", "x_en2ja", [], []),   # -> q18 relevant
    ("q42", "how much did the movers quote us", "x_en2ja", [], []),  # -> q06
    ("q43", "what did the doctor say about the medication", "x_en2ja", [], []),  # -> q05
    ("q44", "when is Kenta's wedding", "x_en2ja", [], []),  # -> q17
    ("q45", "cat vaccination appointment", "x_en2ja", [], []),  # -> q15
    ("q46", "体調悪くて休んでる人", "ja_para",
     ["佐藤さん、昨日から熱があって会社休んでるらしいよ"],
     ["体調管理アプリ入れてみた", "休みの日は家でごろごろしたい"]),
    ("q47", "洗濯機の調子悪いって話", "ja_para",
     ["洗濯機が脱水のときにガタガタすごい音するんだよね"],
     ["洗濯物、雨降りそうだから取り込んでおいて", "調子に乗って食べすぎた"]),
    ("q48", "来年の目標", "ja_para",
     ["来年こそは英語ちゃんと勉強してTOEIC八百点取りたい"],
     ["今年もあっという間だったね", "目標の売上まであと少しです"]),
    ("q49", "荷物の再配達頼んだ？", "ja_para",
     ["不在票入ってたから、夜の時間帯でもう一回持ってきてもらうようにしといた"],
     ["宅配便の仕事って大変そうだよね", "荷物多いから駅まで車で送ってくれない？"]),
    ("q50", "家族旅行の予算", "ja_kw",
     ["夏休みの家族旅行、全部で三十万くらいに抑えたいね"],
     ["旅行の写真、アルバムにまとめたよ", "予算会議の資料まだできてない"]),
]
CROSS_REF = {"q41": "q18", "q42": "q06", "q43": "q05", "q44": "q17", "q45": "q15"}

# ---------------- filler generator (topics disjoint from the queries) ----------------
JA_PEOPLE = ["山本さん", "鈴木くん", "お父さん", "妹", "隣の人", "高橋さん", "あの子", "店長", "伊藤さん", "先輩", "後輩", "うちの兄"]
JA_TIME = ["今朝", "さっき", "昨日の夜", "おととい", "この前", "今日", "朝から", "最近", "さっきまで", "昼過ぎに"]
JA_TOPICS = [
    "天気予報だと明日は晴れるらしい", "雲がすごく低くて雨が降りそう", "日差しが強くて暑かった", "風が冷たくなってきた",
    "テレビのクイズ番組おもしろかった", "ドラマの続きが気になって寝られなかった", "ニュースで台風のことやってた",
    "野球の試合、九回で逆転したんだって", "サッカーの代表戦、引き分けだった", "マラソン大会のボランティアしてた",
    "コンビニの新しいスイーツ食べてみた", "パン屋さんのクロワッサンが美味しかった", "コーヒー豆を新しいのに変えた",
    "お味噌汁の味がちょっと薄かった", "唐揚げ作りすぎちゃった", "抹茶のアイスが好きなんだよね",
    "エクセルの関数がうまく動かない", "メールの返信がたまってる", "プリンターの紙詰まりがまた起きた",
    "キーボードの調子が悪い", "コピー機の前で行列ができてた", "社内チャットの通知が多すぎる",
    "電車が人身事故で遅れてた", "バスが全然来なかった", "自転車のチェーンが外れた", "駅の階段で転びそうになった",
    "ゲームのイベント周回してた", "漫画の新刊が出てた", "ラジオで懐かしい曲が流れてた", "動画を見てたら夜更かししちゃった",
    "植木に水やるの忘れてた", "金魚の水槽を掃除した", "カーテンを洗った", "部屋の電球が切れた",
    "スーパーで卵が安かった", "ドラッグストアでティッシュ買った", "百円ショップで収納ケース見つけた",
    "公園で犬の散歩してる人が多かった", "桜がもうすぐ咲きそう", "紅葉がきれいだった", "セミがうるさかった",
    "眠くて仕方ない", "肩こりがひどい", "あくびが止まらない", "なんか甘いもの食べたい",
]
JA_TAIL = ["よ", "ね", "んだよね", "なあ", "みたい", "らしいよ", "って", "かも", "わ", "", "の", "んだって"]
JA_FILLERS = ["", "", "", "えーと、", "あのー、", "なんか、", "そういえば、", "ていうか、", "まあ、"]

EN_PEOPLE = ["Mike", "my neighbor", "Jenny", "the new guy", "my brother", "Tom", "Lisa", "our manager", "my roommate", "Alex"]
EN_TIME = ["this morning", "last night", "earlier", "yesterday", "the other day", "just now", "all afternoon", "on the weekend"]
EN_TOPICS = [
    "the weather forecast says it'll be sunny tomorrow", "it looked like it was going to rain", "it was really humid outside",
    "that quiz show was hilarious", "I couldn't stop watching that drama", "the news was all about the typhoon",
    "the baseball game went into extra innings", "the soccer match ended in a draw", "there was a marathon downtown",
    "the convenience store has a new dessert", "the croissants at the bakery were amazing", "I switched to a new coffee brand",
    "the miso soup was a little bland", "I made way too much fried chicken", "matcha ice cream is the best",
    "the spreadsheet formula keeps breaking", "my inbox is out of control", "the printer jammed again",
    "my keyboard is acting up", "there was a line at the copier", "the chat notifications never stop",
    "the train was delayed again", "the bus never showed up", "my bike chain came off",
    "I was grinding that game event", "the new manga volume came out", "they played an old song on the radio",
    "I forgot to water the plants", "I cleaned the fish tank", "the light bulb in my room burned out",
    "eggs were on sale at the supermarket", "I found storage boxes at the dollar store", "the cherry blossoms are almost out",
    "the leaves are turning red", "the cicadas were so loud", "I'm so sleepy", "my shoulders are killing me", "I'm craving something sweet",
]
EN_FILLERS = ["", "", "", "um, ", "so ", "like, ", "anyway ", "oh and "]


def gen_filler(rng: random.Random, n_ja: int, n_en: int):
    out = set()
    while len(out) < n_ja:
        p = rng.random()
        f = rng.choice(JA_FILLERS)
        if p < 0.4:
            s = f"{f}{rng.choice(JA_TIME)}{rng.choice(JA_TOPICS)}{rng.choice(JA_TAIL)}"
        elif p < 0.75:
            s = f"{f}{rng.choice(JA_PEOPLE)}が{rng.choice(JA_TIME)}{rng.choice(JA_TOPICS)}って言ってた"
        else:
            a, b = rng.sample(JA_TOPICS, 2)
            s = f"{f}{a}し、{b}{rng.choice(JA_TAIL)}"
        out.add(s)
    ja = sorted(out)
    out = set()
    while len(out) < n_en:
        p = rng.random()
        f = rng.choice(EN_FILLERS)
        if p < 0.4:
            s = f"{f}{rng.choice(EN_TIME)} {rng.choice(EN_TOPICS)}"
        elif p < 0.75:
            s = f"{f}{rng.choice(EN_PEOPLE)} said {rng.choice(EN_TOPICS)} {rng.choice(EN_TIME)}"
        else:
            a, b = rng.sample(EN_TOPICS, 2)
            s = f"{f}{a}, and {b}"
        out.add(s)
    return ja, sorted(out)


def main():
    rng = random.Random(0)
    docs, queries = [], []
    seg_ids = {}
    for qid, q, typ, rel, neg in ITEMS:
        ids = []
        for i, t in enumerate(rel):
            did = f"{qid}_r{i}"
            docs.append({"id": did, "text": t, "kind": "relevant"})
            ids.append(did)
        for i, t in enumerate(neg):
            docs.append({"id": f"{qid}_n{i}", "text": t, "kind": "hard_negative"})
        seg_ids[qid] = ids
        queries.append({"id": qid, "text": q, "type": typ, "relevant": ids})
    for q in queries:
        if q["id"] in CROSS_REF:
            q["relevant"] = seg_ids[CROSS_REF[q["id"]]]
    ja, en = gen_filler(rng, 1300, 700)
    for i, t in enumerate(ja + en):
        docs.append({"id": f"f{i:04d}", "text": t, "kind": "filler"})
    rng.shuffle(docs)
    out = ROOT / "data" / "conv_eval.json"
    out.write_text(json.dumps({"queries": queries, "docs": docs}, ensure_ascii=False, indent=0))
    print(f"wrote {out}: {len(queries)} queries, {len(docs)} docs")


if __name__ == "__main__":
    main()
